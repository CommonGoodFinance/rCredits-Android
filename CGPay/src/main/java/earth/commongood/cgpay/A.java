package earth.commongood.cgpay;

import android.app.Application;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.hardware.Camera;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.preference.PreferenceManager;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.text.NumberFormat;
import java.util.List;

import static java.lang.Integer.parseInt;

/**
 * rCredits POS App
 * <p>
 * Scan a company agent card to "scan in".
 * Scan a customer card to charge him/her, exchange USD for rCredits, or give a refund.
 * See functional specs (SRS): https://docs.google.com/document/d/1bSVn_zaS26SZgtTg86bvR51hwEWOMynVmOEjEbIUNw4
 * See RPC API specs: https://docs.google.com/document/d/1fZE_wmEBRTBO9DAZcY4ewQTA70gqwWqeqkUnJSfWMV0
 * <p>
 * This class also holds global static variables and constants.
 * <p>
 * The application uses the open source Zxing project.
 * The package com.google.zxing.client.android has been changed to zxing.client.android
 * All other changes are commented with "cgf". Small modifications have been made to the following classes.
 * CaptureActivity
 * CameraManager
 * ViewfinderView
 * <p>
 * Some cameras don't tell Android that their camera faces front (and therefore needs flipping), so a separate
 * version (rposb) should be built with flip=true in onCreate()
 * <p>
 * TESTS:
 * Upgrade AND Clean Install (run all relevant tests for each)
 * (assuming charge permission for cashier, all permissions except usd4r for agent)
 * A 0. Sign in, sign out. Charge customer $9,999.99. Get error message.
 * A 1. (signed out) Charge cust 11 cent. Check "balance" button. Repeat in landscape, charging 12 cents.
 * 2. (signed in) (undo and bal buttons show). Undo. Check "balance" button. Sign out (no balance or undo buttons).
 * 3. (signed in) refund customer 13 cents, check balance and undo, undo, check balance.
 * 4. (signed in) give customer 14 cents in rCredits for USD, check balance and undo, undo, check balance.
 * B 0. Install clean. Wifi off. Try scanning agent and customer card, check for good error messages. Wifi on, try scanning customer card (check for error message), sign in/out.
 * 1-4. Repeat A1-4 with wifi off, using amounts 21, 22, 23, and 24 cents.
 * C 1. (wifi off, signed out) Scan cust, wifi on, charge 31 cents.
 * 2. (wifi on, signed out) Scan cust, wifi off, charge 32 cents.
 * D 1. (wifi off) Sign in, scan cust, wifi on, charge 41 cents.
 * 2. (wifi off) Sign in, wifi on, scan cust, wifi off, charge 42 cents.
 * 3. (wifi off) Sign in, wifi on, scan cust, charge 43 cents.
 * 4. (wifi on) Sign in, wifi off, scan cust, charge 44 cents.
 * 5. (wifi on) Sign in, wifi off, scan cust, wifi on, charge 45 cents.
 * 6. (wifi on) Sign in, scan cust, wifi off, charge 46 cents.
 * E 1. (wifi on, signed in) Scan cust, charge 51 cents, wifi off, undo.
 * 2. (wifi off, signed in) Scan cust, charge 52 cents, wifi on, undo.
 * E After online reconciles, should see transactions for:
 * 11, 12, -12, -13, 13, -14, 14, 21, 31-32, 41-46, 51, -51, 52, -52 (or maybe neither 52 nor -52)
 */
public class A extends Application {
	public static boolean fakeScan = false; // for testing in simulator, without a webcam
	public static Context context;
	public static Resources resources;
	public static int versionCode; // version number of this software
	public static String versionName; // version name of this software
	public static SharedPreferences settings = null;
	private static ConnectivityManager cm = null;

	public static Long timeFix = 0L; // how much to add to device's time to get server time
	//    public static String update = null; // URL of update to install on restart, if any ("1" means already downloaded)
	public static String failMessage = null; // error message to display upon restart, if any
	public static String sysMessage = null; // message from system to display when convenient
	public static String deviceId = null; // set once ever in storage upon first scan-in, read upon startup
	public static String debugString = null; // for debug messages
	public static boolean flip; // whether to flip the scanned image (for front-facing cameras)
	public static boolean positiveId; // did online customer identification succeed
	public static String packageName; // resource package
	public static B bTest = null;
	public static B bReal = null;
	public static final boolean hasMenu = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB);

	// variables that get reset when changing mode between testing and not testing
	public static B b = null; // stuff that goes with real or test Db
	public static boolean stop = false; // <app is ending>
	public static boolean wifiOff = false; // force wifi off
	public static boolean neverAskForId = false; // ask customer for ID when first time or offline
	public static boolean selfhelp = false; // whether to skip the photoID step and assume charging for default goods
	public static String agent = null; // QID of device operator, set upon scan-in (eg NEW:AAB), otherwise null
	public static boolean co = false; // the device owner is a company
	public static boolean signedIn = false; // a manager is signed in (OR the device owner is a person)
	public static String agentName = null; // set upon scan-in
	public static boolean goingHome = false; // flag set to return to main activity

	// global variables that Periodic process must not screw up (refactor these to be local and passed)
	public static String customerName = null; // set upon identifying a customer
	public static String balance = null; // message about last customer's balance
	public static String undo = null; // message about reversing last transaction
	public static Long undoRow = null; // row number of last transaction in local db (to undo or not duplicate)
	public static String lastQid = null; // person whose balance or undo can be shown
	public static String httpError = null; // last HTTP failure message

	public static List<String> descriptions; // set upon scan-in
	public final static String DESC_REFUND = "refund";
	public final static String DESC_PAY = "pay";
	public final static String DESC_CHARGE = "charge";
	public final static String DESC_USD_IN = "USD in";
	public final static String DESC_USD_OUT = "USD out";

	public static int can = 0; // what the user can do (permissions come from PrefsActivity, limited by server)
	public final static int CAN_CHARGE = 0;
	public final static int CAN_UNDO = 1;
	public final static int CAN_R4USD = 2;
	public final static int CAN_USD4R = 3;
	public final static int CAN_REFUND = 4;
	public final static int CAN_BUY = 5;
	public final static int CAN_U6 = 6; // unused
	public final static int CAN_MANAGE = 7; // never true unless signed in

	public final static int CAN_CASHIER = 0; // how far right to shift bits for cashier permissions
	public final static int CAN_AGENT = 8; // how far right to shift bits for agent permissions

	public final static String MSG_DOWNLOAD_SUCCESS = "Update download is complete.";

	// values for status field in table txs
	public final static int TX_CANCEL = -1; // transaction has been canceled offline, but may exist on server
	public final static int TX_PENDING = 0; // transaction is being sent to server
	public final static int TX_OFFLINE = 1; // connection failed, offline transaction is waiting to be uploaded
	public final static int TX_DONE = 2; // completed transaction is on server

	public final static int REAL_PERIOD = 20 * 60; // (20 mins.) how often to tickle when not testing
	public final static int TEST_PERIOD = 20; // (20 secs.) how often to tickle
	public final static int PIC_W = 900; // standard pixel width of photos
	public final static int PIC_W_OFFLINE = 45; // standard pixel width of photos stored for offline use
//	public final static int PIC_H = 1200; // standard pixel height of photos
//	public final static int PIC_H_OFFLINE = 60; // standard pixel height of photos stored for offline use
	public final static double PIC_ASPECT = .75; // standard photo aspect ratio

	public final static int TX_AGENT = -1; // agent flag in customer AGT_FLAG field (negative to distinguish)
	public final static String NUMERIC = "^-?\\d+([,\\.]\\d+)?$"; // with exponents: ^-?\d+([,\.]\d+)?([eE]-?\d+)?$
	public final static int MAX_CREDITLINE_LEN = 20; // anything longer than this is a photoId in the creditLine (db) field

	private final static String PREFS_NAME = "CGPay";
	public final static String PROMO_SITE = "https://CommonGood.earth";
	public final static String REAL_PATH = "https://<region>.commongood.earth"; // the real server (rc2.me fails)
	public final static String TEST_PATH = "https://demo.commongood.earth"; // for demo/test cards
//	private final static String TEST_API_PATH = "http://192.168.2.101/cgmembers-frame/cgmembers/pos"; // testing locally
	private final static String API_PATH = "/pos"; // where on server is the API
	private final static int TIMEOUT = 7; // how many seconds before HTTP POST times out

	@Override
	public void onCreate() {
		A.log(0);
		super.onCreate();

		A.context = this;
		A.resources = getResources();
		A.packageName = getApplicationContext().getPackageName();
		A.deviceId = getStored("deviceId");
		A.neverAskForId = getStored("neverAskForId").equals("1");
		try {
			PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
			A.versionCode = pInfo.versionCode;
			A.versionName = pInfo.versionName + "";
		} catch (PackageManager.NameNotFoundException e) {
			e.printStackTrace();
		}

		A.cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

		A.bTest = new B(true);
		A.bReal = new B(false);
		A.setMode(false); // not testing

		Camera.CameraInfo info = new Camera.CameraInfo();
		if (!A.fakeScan) {
			Camera.getCameraInfo(0, info);
			flip = (Camera.getNumberOfCameras() == 1 && info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT);
			//flip = true; versionCode = "0" + versionCode; // uncomment for old devices with only an undetectable front-facing camera.
		}
		PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
		System.setProperty("http.keepAlive", "false"); // as of 8/16/2016 this prevent background http POSTs from being duplicated
		// ... and possibly prevents timing bugs between background and foreground http POSTs?
		A.log(9);
	}

	/**
	 * Return true if we can connect to the internet.
	 */
	public static boolean connected() {
		NetworkInfo ni = A.cm.getActiveNetworkInfo();
		return (!A.wifiOff && ni != null && ni.isAvailable() && ni.isConnected());
	}

	/**
	 * Change from test mode to real mode or vice versa
	 *
	 * @param testing: <new mode is test mode (otherwise real)>
	 */
	public static void setMode(boolean testing) {
		A.log(0);
		A.b = testing ? bTest : bReal;
		if (A.empty(A.agent)) { // just starting up
			A.useDefaults(); // get parameters
		} else if (A.co && A.signedIn) A.signOut(); // must postcede setting A.b (personal accounts stay signed in)

		A.balance = A.undo = null;
		b.launchPeriodic();
		A.log(9);
	}

	public static void useDefaults() {
		A.log(0);
		Json dft = A.b.defaults;
		if (dft == null) {
			A.agent = A.agentName = null;
			A.descriptions = null;
			A.can = 0;
		} else {
			A.agent = A.ownerQid();
			A.descriptions = dft.getArray("descriptions");
			A.can = Integer.valueOf(dft.get("can"));
			A.co = !A.empty(dft.get("company"));
			A.signedIn = !A.co;
			A.agentName = A.co ? dft.get("company") : dft.get("person");
		}
		A.noUndo(); // but leave balance accessible, if any
		A.customerName = A.httpError = null;
		A.log(9);
	}

	public static String ownerQid() {
		return rCard.co(A.b.db.getField("qid", "members", DbSetup.IS_AGENT));
	}

	/**
	 * Reset all global variables to the "signed out" state.
	 */
	public static void signOut() {
		A.log(0);
		if (A.co && A.signedIn) A.useDefaults();
		A.log(9);
	}

	public static boolean selfhelping() {
		return (A.selfhelp && !A.signedIn);
	}

	/**
	 * Return a "shared preference" (stored value) as a string (the only way we store anything).
	 *
	 * @param name
	 * @return the stored value ("" if none)
	 */
	public static String getStored(String name) {
		if (A.settings == null) A.settings = A.context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
		return settings.getString(name, "");
	}

	public static void setStored(String name, String value) {
		A.settings.edit().putString(name, value).commit();
	}

	/**
	 * Send a request to the server and return its response.
	 *
	 * @param region: the agent's region
	 * @param pairs:  name/value pairs to send with the request (returned with extra info)
	 * @return: the server's response. null if failure (with message in A.httpError)
	 */
	public static HttpURLConnection sendPostRequest(String region, Pairs pairs) {
		A.log(0);
		pairs.add("agent", A.empty(A.agent) ? "" : A.agent); // test, otherwise pairs.toPost() produces "null"
		pairs.add("device", A.deviceId);
		pairs.add("version", A.versionCode + "");
		pairs.add("region", region);
		if (!A.connected()) return A.log("not connected") ? null : null;
//			String data = pairs.get("data");
//			if (data != null) A.log("datalen = " + data.length());
		try {
			URL url = new URL((A.b.test ? TEST_PATH : REAL_PATH).replace("<region>", region) + API_PATH);
			HttpURLConnection conx = sendPost(url, pairs.toPost());
			if (conx == null) return null;
			A.log("status: " + conx.getResponseCode());
			return conx;
		} catch (IOException e) {A.log(e);}

		A.log("no connection");
		return null;
	}

	/**
	 * Send a request to the server and return its response.
	 *
	 * @param region: the agent's region
	 * @param pairs:  name/value pairs to send with the request (returned with extra info)
	 * @return: the server's response. null if failure (with message in A.httpError)
	 */
	public static String post(String region, Pairs pairs) {
		HttpURLConnection conx = sendPostRequest(region, pairs);
		if (conx == null) return null;
		String results = "";
		try {
			BufferedReader in = new BufferedReader(new InputStreamReader(conx.getInputStream()));
			String decodedString;
			while ((decodedString = in.readLine()) != null) results += decodedString;
			in.close();
		} catch (IOException e) {
			A.log(e);
		} finally {conx.disconnect();}

		A.log(results);
		return results;
	}

	/**
	 * Send a request to the server and return a JPEG.
	 *
	 * @param region: the agent's region
	 * @param pairs:  name/value pairs to send with the request (returned with extra info)
	 * @return: the photo from server's response. null if failure (with message in A.log)
	 */
	public static byte[] postForPhoto(String region, Pairs pairs) {
		A.log(0);
		Bitmap res = null;
		HttpURLConnection conx = sendPostRequest(region, pairs);
		if (conx == null) return null;

		try {
			InputStream in = new BufferedInputStream(conx.getInputStream());

			res = A.makeBitmap(in);
			A.log("post: " + res);
		} catch (IOException e) {A.log(e);}

		conx.disconnect();
		return A.bm2bray(res);
	}

	/**
	 * POST data to the server and return an open connection.
	 * @param url: where to send the request
	 * @param data: parameter list
	 */
	private static HttpURLConnection sendPost(URL url, String data) {
		try {
			HttpURLConnection conx = (HttpURLConnection) url.openConnection();
			if (conx == null) return null;
			conx.setUseCaches(false);
			conx.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
			conx.setRequestProperty("Charset", "utf-8");
			conx.setRequestProperty("Accept", "application/json, text/plain, */*");
			conx.setRequestProperty("Accept-Charset", "utf-8,*");
			conx.setRequestMethod("POST");
			conx.setRequestProperty("Content-Length", Integer.toString(data.length()));
//			conx.setFixedLengthStreamingMode(data.getBytes().length);
//			conx.setChunkedStreamingMode(1000);
			conx.setConnectTimeout(A.TIMEOUT * 1000);
			conx.setReadTimeout(A.TIMEOUT * 1000);
			conx.setDoOutput(true);
			conx.setDoInput(true);

			OutputStreamWriter out = new OutputStreamWriter(conx.getOutputStream());
			out.write(data);
			out.flush();
			out.close();

			return conx;
		} catch (IOException e) {A.log(e);}

		return null;
	}

	/**
	 * Get a string response from the server
	 *
	 * @param region: which regional server to ask
	 * @param pairs:  name/value pairs to send with the request (including op) (returned with extra info)
	 * @return the response
	 */
	public static Json apiGetJson(String region, Pairs pairs) {
		A.log(0);
		String response = A.post(region, pairs);
		A.log("| |" + response + "| |");
		if (response == null) {
			A.log("got null");
			return null;
		}

		A.log("got!" + response);
		A.log(9);
		return Json.make(response);
	}
	/**
	 * Get the customer's photo from his/her server.
	 *
	 * @param qid:  the customer's account ID
	 * @param code: the customer's rCard security code
	 * @return: the customer's photo, as a byte array (length < 100 if invalid, null if no wifi)
	 */
	public static byte[] apiGetPhoto(String qid, String code) {
		A.log(0);
		Pairs pairs = new Pairs("op", "photo");
		pairs.add("member", qid);
		pairs.add("code", code);
		byte[] response = A.postForPhoto(rCard.qidRegion(qid), pairs);
		A.log("response" + response);
//		Json obj = new Json(response);
		byte[] res = response == null ? null : response;
		A.log("photo len=" + (res == null ? 0 : res.length));
		A.log(9);
		return res;
	}
	public static Bitmap makeBitmap(InputStream  by) {
		Bitmap bitmap;
		bitmap = BitmapFactory.decodeStream(by);
		return bitmap;
	}
	public static Bitmap makeBitmap(byte[] by) {
		Bitmap bitmap;
		bitmap= BitmapFactory.decodeByteArray(by, 0, by.length);
		return bitmap;
	}
	/**
	 * Read the system log file.
	 */
	public static String sysLog() {
		String res = "";
		String line;
		final int limit = 20000; // number of characters to return
//        int newLen;
		String mark = "now=" + A.now();
		A.log(mark);

		try {
	        /*
            Process process = Runtime.getRuntime().exec("logcat -d -" + limit); // better, if it works
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            while ((line = bufferedReader.readLine()) != null) res += line + "\n";
            */
			Process process = Runtime.getRuntime().exec("logcat");
			BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
			while (true) {
				line = bufferedReader.readLine() + "\n";
				res = A.substr(res + line, 0, limit);
				if (line.indexOf(mark) > -1) return res; // otherwise loop never ends (readLine of logcat is never null?)
/*
                line = A.substr(line, 0, limit - 1) + "\n";
                newLen = res.length() + line.length();
                res = res.substring(newLen > limit ? newLen - limit : 0) + line; // this is correct but takes thought
                */
			}
//            return res;
		} catch (Exception e) { // java.io.IOException
			A.log(e);
			return "problem in sysLog: " + e.getMessage();
		}

	}

	/**
	 * Return a json-encoded string of information about the device.
	 *
	 * @return: json-encoded string of associative array
	 */
	public static String getDeviceData() {
		Json j = new Json("{}"); // the overall result
		j.put("board", Build.BOARD);
		j.put("brand", Build.BRAND);
		j.put("device", Build.DEVICE);
		j.put("display", Build.DISPLAY);
		j.put("fingerprint", Build.FINGERPRINT);
		j.put("hardware", Build.HARDWARE);
		j.put("id", Build.ID);
		j.put("manufacturer", Build.MANUFACTURER);
		j.put("model", Build.MODEL);
		j.put("product", Build.PRODUCT);
		j.put("serial", Build.SERIAL);
		j.put("tags", Build.TAGS);
		j.put("time", Build.TIME + "");
		j.put("type", Build.TYPE);
		j.put("user", Build.USER);
		j.put("kLeft", A.b.db.kLeft() + "");

		j.put("defaults", b.defaults.toString());
		return j.toString();
	}

	/**
	 * Set the device's system time (to match the relevant server's clock) unless time is null.
	 * NOTE: some devices crash on attempt to set time (even with SET_TIME permission), so we fudge it.
	 *
	 * @param time: unixtime, as a string (or null if time is not available)
	 * @return <time was set successfully>
	 */
	public static boolean setTime(String time) {
		A.log(0);
		if (time == null || time.equals("")) return false;

		//AlarmManager am = (AlarmManager) A.context.getSystemService(Context.ALARM_SERVICE);
		//am.setTime(time * 1000L);
		A.timeFix = Long.parseLong(time) - now0();
		A.log(9);
		return true;
	}

	/**
	 * Add a string parameter to the activity we are about to launch.
	 *
	 * @param intent: the intended activity
	 * @param key:    parameter name
	 * @param value:  parameter value
	 */
	public static void putIntentString(Intent intent, String key, String value) {
		String pkg = intent.getComponent().getPackageName();
		intent.putExtra(pkg + "." + key, value);
	}

	/**
	 * Return a string parameter passed to the current activity.
	 *
	 * @param intent: the intent of the current activity
	 * @param key:    parameter name
	 * @return: the parameter's value
	 */
	public static String getIntentString(Intent intent, String key) {
		String pkg = intent.getComponent().getPackageName();
		return intent.getStringExtra(pkg + "." + key);
	}

	/**
	 * Say whether the agent can do something
	 *
	 * @param permission: bits representing things the agent might do
	 * @return true if the agent can do it
	 */
	public static boolean can(int permission) {
		int can = A.can >> (A.signedIn ? CAN_AGENT : CAN_CASHIER);
//        A.log("permission=" + permission + " can=" + can + " A.can=" + A.can + " signed in:" + A.signedIn + " 1<<perm=" + (1<<permission));
		return ((can & (1 << permission)) != 0);
	}

	public static void setCan(int bit, boolean how) {
		A.can = how ? (A.can | (1 << bit)) : (A.can & ~(1 << bit));
	}

	/**
	 * Say whether the customer's balance is to be kept secret.
	 *
	 * @param balance: the balance string -- starts with an asterisk if it is secret
	 * @return <balance is secret or null>
	 */
	public static boolean isSecret(String balance) {
		return A.empty(balance) ? true : balance.substring(0, 1).equals("*");
	}

	/**
	 * Return a formatted dollar amount (always show cents, no dollar sign).
	 *
	 * @param n:      the amount to format
	 * @param commas: <include commas, if appropriate>
	 * @return: the formatted amount
	 */
	public static String fmtAmt(Double n, boolean commas) {
		NumberFormat num = NumberFormat.getInstance();
		num.setMinimumFractionDigits(2);
		String result = num.format(n);
		return commas ? result : result.replaceAll(",", "");
	}
	public static String fmtAmt(String v, boolean commas) {
		return fmtAmt(Double.valueOf(v), commas);
	}

	/**
	 * Return a suitable message for when the "Show Customer Balance" button is pressed.
	 *
	 * @param customerName
	 * @param balance
	 * @param creditLine
	 * @param did
	 * @return
	 */
	public static String balanceMessage(String customerName, String balance, String creditLine, String did) {
		if (A.isSecret(balance)) return null;
		String disabledBy = !A.co ? "you" : "account-holder";
		creditLine = A.empty(creditLine) ? " (disabled by " + disabledBy + ")" : A.fmtAmt(creditLine, true);
		return (!A.co ? "" : ("Customer: " + customerName + "\n\n")) +
				"Balance: $" + A.fmtAmt(balance, true) + "\n" +
				"Credit Line: $" + creditLine +
				A.nn(did); // if not empty, did includes leading blank lines
	}

	/**
	 * Return a suitable message for when the "Show Customer Balance" button is pressed.
	 *
	 * @param customerName: customer name (including agent and company name, for company agents)
	 * @return: a suitable message or null if the balance is secret (or no data)
	 */
	public static String balanceMessage(String customerName, Json json) {
		if (json == null) return null;
		String balance = json.get("balance");
		String creditLine = json.get("creditLine"); // actually this is the customer's credit line
		String did = json.get("did");

		return balanceMessage(!A.co ? A.agentName : customerName, balance, creditLine, did);
	}

	public static String nameAndCo(String person, String company) {
		return A.empty(company)
				? person
				: (A.empty(person) ? company : (person + "\nfor " + company));
	}

	public static String hash(String text) {
		A.log(0);
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			md.update(text.getBytes("UTF-8")); // Change this to "UTF-16" ifeeded
			A.log(9);
			return bytesToHex(md.digest());
		} catch (Exception e) {
			b.report(e.getMessage());
			return null;
		}
	}

	public static String bytesToHex(byte[] a) {
		StringBuilder sb = new StringBuilder(a.length * 2);
		for (byte b : a) sb.append(String.format("%02x", b & 0xff));
		return sb.toString();
	}

	public static byte[] hexToBytes(String str) {
		if (str == null || str.length() < 2) return null;
		int len = str.length() / 2;
		byte[] buffer = new byte[len];
		for (int i = 0; i < len; i++) {
			buffer[i] = (byte) parseInt(A.substr(str, i * 2, 2), 16);
		}
		return buffer;
	}

	/**
	 * Shrink the image and reduce quality, before storing.
	 *
	 * @param image
	 * @return: the shrunken image
	 */
	public static byte[] shrink(byte[] image) {
		A.log(0);
		Bitmap bm = A.bray2bm(image);
		int width0 = bm.getWidth();
		if (width0 == 0) return A.bm2bray(bm);

		int width = PIC_W_OFFLINE;
		int height = (int) bm.getHeight() * width / width0;
		bm = scale(bm, width);

		A.log("shrink img len=" + image.length + " bm size=" + (bm.getRowBytes() * height));

		Bitmap bmGray = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
		Canvas c = new Canvas(bmGray);
		Paint paint = new Paint();
		ColorMatrix cm = new ColorMatrix();
		cm.setSaturation(0);
		ColorMatrixColorFilter f = new ColorMatrixColorFilter(cm);
		paint.setColorFilter(f);
		c.drawBitmap(bm, 0, 0, paint);

		A.log(9);
		return A.bm2bray(bmGray);
	}
	public static Bitmap scale(Bitmap bm, int width) {
		A.log("shrink img to w=" + width);
		int width0 = bm.getWidth();
		if (width0 == 0) return bm;
		int height = (int) bm.getHeight() * width / width0;
		return Bitmap.createScaledBitmap(bm, width, height, true);
	}
	public static ContentValues list(String keyString, String[] values) {
		String[] keys = keyString.split(" ");
		ContentValues map = new ContentValues(0);
		for (int i = 0; i < keys.length; i++) map.put(keys[i], values.length > i ? values[i] : null);
		return map;
	}

	public static boolean log(String s, int traceIndex) {
		if (A.b != null) logDb(s, traceIndex > 0 ? traceIndex + 1 : 2); // redundant logging
		if (traceIndex != 0) {
			StackTraceElement l = new Exception().getStackTrace()[traceIndex]; // look at caller
			s += String.format(" - %s.%s %s", l.getClassName().replace("earth.commongood.cgpay.", ""), l.getMethodName(), l.getLineNumber());
		}
		Log.i("DEBUG", s);
		return true;
	}
	public static boolean log(String s) {
		return log(s, 2);
	}
	public static boolean log(StringBuffer s) {
		return log(s.toString(), 2);
	}
	public static boolean log(int n) {
		return log("p" + n, 2);
	}

	/**
	 * Log the given message in the log table, possibly for reporting to rCredits admin
	 *
	 * @param s: the message
	 */
	public static boolean logDb(String s, int traceIndex) {
		StackTraceElement l = new Exception().getStackTrace()[traceIndex]; // look at caller
		ContentValues values = new ContentValues();
		values.put("class", l.getClassName().replace("org.cg.", ""));
		values.put("method", l.getMethodName());
		values.put("line", l.getLineNumber());
		values.put("time", A.now());
		values.put("what", s);

		A.b.db.enoughRoom(); // always make room for logging, if there's any room for anything
		try {
			A.b.db.insert("log", values);
		} catch (Db.NoRoom noRoom) {
		} // nothing to be done
		return true;
	}

	/**
	 * Log the exception.
	 *
	 * @param e: exception object
	 * @return true for the convenience of callers, for example: return Log(e) ? null : null;
	 */
	public static boolean log(Exception e) {
		String trace = Log.getStackTraceString(e).replace("earth.commongood.cgpay.", "").replace("android.", ".");
		return A.log(e.getMessage() + "! " + terseTrace(trace), 0);
	}

	/**
	 * Return an abbreviated stacktrace for common known issues
	 *
	 * @param s
	 * @return s abbreviated, if possible
	 */
	private static String terseTrace(String s) {
		String msg;
		return (
				s.contains(msg = "Network is unreachable") ||
						s.contains(msg = "SSLPeerUnverifiedException") ||
						s.contains(msg = "Connection refused") ||
						s.contains(msg = "No address associated with hostname") ||
						s.contains(msg = "org.apache.http.conn.ConnectTimeoutException") ||
						s.contains(msg = "at java.net.InetAddress.lookupHostByName") ||
						s.contains(msg = "java.net.SocketTimeoutException") ||
						s.contains(msg = "javax.net.ssl.SSLException") ||
						s.contains(msg = "org.apache.http.conn.HttpHostConnectException") ||
						s.contains(msg = "java.net.SocketException")
		) ? msg : s;
	}

	public static byte[] bm2bray(Bitmap bm) {
		A.log(0);
		ByteArrayOutputStream stream = new ByteArrayOutputStream();
		bm.compress(Bitmap.CompressFormat.PNG, 100, stream);
		A.log(9);
		return stream.toByteArray();
	}

	public static Bitmap bray2bm(byte[] bray) {
//		BitmapFactory.Options options = new BitmapFactory.Options();
//		options.inPreferredConfig = Bitmap.Config.RGB_565;
		A.log("bray2||" + bray.length + "|" + bray);
//		Bitmap bit=BitmapFactory.decodeByteArray(bray, 0, bray.length);
		return BitmapFactory.decodeByteArray(bray, 0, bray.length);
	}

	/**
	 * Return the named image.
	 *
	 * @param photoName: the image filename (no extension)
	 * @return: a byte array image
	 */
	public static byte[] photoFile(String photoName) {
		A.log(0);
		int photoResource = A.resources.getIdentifier(photoName, "drawable", A.packageName);
		Bitmap bm = BitmapFactory.decodeResource(A.resources, photoResource);
		ByteArrayOutputStream stream = new ByteArrayOutputStream();
		bm.compress(Bitmap.CompressFormat.PNG, 100, stream);
		return stream.toByteArray();
	}

	/**
	 * Return a substring of a given length, like in PHP.
	 *
	 * @param s
	 * @param start
	 * @param count (if there are not enough chars starting at start, return what there are)
	 * @return
	 */
	public static String substr(String s, int start, int count) {
		int len = s.length();
		if (start + count > len) count = len - start;
		return s.substring(start, start + count);
	}

	public static void noUndo() {
		A.undo = null;
		A.undoRow = null;
	}

	public static String nn(String s) {
		return s == null ? "" : s;
	}
	public static boolean empty(String s) {
		return nn(s).length() == 0;
	}
	public static boolean empty(byte[] b) {
		return (b == null || b.length == 0);
	}
	public static String nnc(String s) {
		return empty(s) ? "" : (s + ", ");
	}
	public static String spnn(String s) {
		return empty(s) ? "" : (" " + s);
	}
	public static String nnsp(String s) {
		return empty(s) ? "" : (s + " ");
	}
	//    public static String nn(CharSequence s) {return s == null ? "" : s.toString();}
	public static Long n(String s) {
		return s == null ? null : Long.parseLong(s);
	}
	public static String ucFirst(String s) {
		return s.length() < 1 ? "" : (s.substring(0, 1).toUpperCase() + s.substring(1));
	}
	public static String t(int stringResource) {
		return A.resources.getString(stringResource);
	}
	public static Long now0() {
		return System.currentTimeMillis() / 1000L;
	}
	public static Long now() {
		return A.timeFix + A.now0();
	}
	public static Long today() {
		return now() - (now() % (60 * 60 * 24));
	}
	public static Long daysAgo(int daysAgo) {
		return now() - daysAgo * 60 * 60 * 24;
	}
}
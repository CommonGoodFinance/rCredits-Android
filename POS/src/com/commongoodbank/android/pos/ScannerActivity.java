/* $Id: $
 */
package com.commongoodbank.android.pos;

import android.app.Activity;
import android.os.Bundle;


/**
 *
 * @version $Revision: $
 * @author <a href="mailto:blake.meike@gmail.com">G. Blake Meike</a>
 */
public class ScannerActivity  extends Activity {
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.scan);
    }
}

/*
 * Copyright (C) 2011 ZXing authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zxing.client.android.encode;

import android.telephony.PhoneNumberUtils;

import java.util.regex.Pattern;

/**
 * Encodes contact information according to the vCard format.
 *
 * @author Sean Owen
 */
final class VCardContactEncoder extends ContactEncoder {

  private static final Pattern RESERVED_VCARD_CHARS = Pattern.compile("([\\\\,;])");
  private static final Pattern NEWLINE = Pattern.compile("\\n");
  private static final Formatter VCARD_FIELD_FORMATTER = new Formatter() {
    @Override
    public String format(String source) {
      return NEWLINE.matcher(RESERVED_VCARD_CHARS.matcher(source).replaceAll("\\\\$1")).replaceAll("");
    }
  };
  private static final char TERMINATOR = '\n';

  @Override
  public String[] encode(Iterable<String> names,
                         String organization,
                         Iterable<String> addresses,
                         Iterable<String> phones,
                         Iterable<String> emails,
                         Iterable<String> urls,
                         String note) {
    StringBuilder newContents = new StringBuilder(100);
    newContents.append("BEGIN:VCARD").append(TERMINATOR);
    newContents.append("VERSION:3.0").append(TERMINATOR);
    StringBuilder newDisplayContents = new StringBuilder(100);
    appendUpToUnique(newContents, newDisplayContents, "N", names, 1, null);
    append(newContents, newDisplayContents, "ORG", organization);
    appendUpToUnique(newContents, newDisplayContents, "ADR", addresses, 1, null);
    appendUpToUnique(newContents, newDisplayContents, "TEL", phones, Integer.MAX_VALUE, new Formatter() {
      @Override
      public String format(String source) {
        return PhoneNumberUtils.formatNumber(source);
      }
    });
    appendUpToUnique(newContents, newDisplayContents, "EMAIL", emails, Integer.MAX_VALUE, null);
    appendUpToUnique(newContents, newDisplayContents, "URL", urls, Integer.MAX_VALUE, null);
    append(newContents, newDisplayContents, "NOTE", note);
    newContents.append("END:VCARD").append(TERMINATOR);
    return new String[] { newContents.toString(), newDisplayContents.toString() };
  }
  
  private static void append(StringBuilder newContents, 
                             StringBuilder newDisplayContents,
                             String prefix, 
                             String value) {
    doAppend(newContents, newDisplayContents, prefix, value, VCARD_FIELD_FORMATTER, TERMINATOR);
  }
  
  private static void appendUpToUnique(StringBuilder newContents, 
                                       StringBuilder newDisplayContents,
                                       String prefix, 
                                       Iterable<String> values, 
                                       int max,
                                       Formatter formatter) {
    doAppendUpToUnique(newContents,
                       newDisplayContents,
                       prefix,
                       values,
                       max,
                       formatter,
                       VCARD_FIELD_FORMATTER,
                       TERMINATOR);
  }

}

/*
 * Copyright (C) 2012 Steven Luo
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

package com.offsec.nhterm;

import android.content.Intent;
import android.util.Log;

/*
 * New procedure for launching a command in ATE.
 * Build the path and arguments into a Uri and set that into Intent.data.
 * intent.data(new Uri.Builder().setScheme("file").setPath(path).setFragment(arguments))
 *
 * The old procedure of using Intent.Extra is still available but is discouraged.
 */

public final class RunScriptNetHunterLogin extends RemoteInterface {

    private static final String ACTION_RUN_SCRIPT_NH_LOGIN = "com.offsec.nhterm.RUN_SCRIPT_NH_LOGIN";
    private static final String EXTRA_WINDOW_HANDLE = "com.offsec.nhterm.window_handle";
    private static final String EXTRA_INITIAL_COMMAND = "com.offsec.nhterm.iInitialCommand";

    @Override
    protected void handleIntent() {
        TermService service = getTermService();
        if (service == null) {
            finish();
            return;
        }

        Intent myIntent = getIntent();
        String action = myIntent.getAction();
        if (action.equals(ACTION_RUN_SCRIPT_NH_LOGIN)) {
            /* Someone with the appropriate permissions has asked us to
               run a script */
            String handle = myIntent.getStringExtra(EXTRA_WINDOW_HANDLE);
            String command;
            // If Intent.data not used then fall back to old method.

            command=myIntent.getStringExtra(EXTRA_INITIAL_COMMAND);
            Log.d("RUN_SCRIPT_NH: LGN",command);

            if (handle != null) {
                // Target the request at an existing window if open
                handle = appendToWindow(handle, command, ShellType.KALI_LOGIN_SHELL);
            } else {
                // Open a new window
                handle = openNewWindow(command, ShellType.KALI_LOGIN_SHELL);
            }
            Intent result = new Intent();
            result.putExtra(EXTRA_WINDOW_HANDLE, handle);
            setResult(RESULT_OK, result);

            finish();
        } else {
            super.handleIntent();
        }
    }
}

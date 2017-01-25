/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.os.Build;
import android.support.annotation.RequiresApi;
import android.text.TextUtils;

import com.offsec.nhterm.R;

import com.offsec.nhterm.compat.ActionBarCompat;
import com.offsec.nhterm.compat.ActivityCompat;
import com.offsec.nhterm.compat.AndroidCompat;
import com.offsec.nhterm.compat.MenuItemCompat;
import com.offsec.nhterm.emulatorview.EmulatorView;
import com.offsec.nhterm.emulatorview.TermSession;
import com.offsec.nhterm.emulatorview.UpdateCallback;
import com.offsec.nhterm.emulatorview.compat.ClipboardManagerCompat;
import com.offsec.nhterm.emulatorview.compat.ClipboardManagerCompatFactory;
import com.offsec.nhterm.emulatorview.compat.KeycodeConstants;
import com.offsec.nhterm.util.SessionList;
import com.offsec.nhterm.util.TermSettings;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import android.view.View.OnClickListener;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import static android.R.attr.button;

/**
 * A terminal emulator activity.
 */

public class Term extends Activity implements UpdateCallback, SharedPreferences.OnSharedPreferenceChangeListener, View.OnClickListener {
    /**
     * The ViewFlipper which holds the collection of EmulatorView widgets.
     */
    private TermViewFlipper mViewFlipper;

    /**
     * The name of the ViewFlipper in the resources.
     */
    private static final int VIEW_FLIPPER = R.id.view_flipper;

    private SessionList mTermSessions;

    private TermSettings mSettings;

    private final static int SELECT_TEXT_ID = 0;
    private final static int COPY_ALL_ID = 1;
    private final static int PASTE_ID = 2;
    private final static int SEND_CONTROL_KEY_ID = 3;
    private final static int SEND_FN_KEY_ID = 4;

    private boolean mAlreadyStarted = false;
    private boolean mStopServiceOnFinish = false;

    private Intent TSIntent;

    public static final int REQUEST_CHOOSE_WINDOW = 1;
    public static final String EXTRA_WINDOW_ID = "com.offsec.nhterm.window_id";
    private int onResumeSelectWindow = -1;
    private ComponentName mPrivateAlias;

    private PowerManager.WakeLock mWakeLock;
    private WifiManager.WifiLock mWifiLock;
    // Available on API 12 and later
    private static final int WIFI_MODE_FULL_HIGH_PERF = 3;

    private boolean mBackKeyPressed;

    private static final String ACTION_PATH_BROADCAST = "com.offsec.nhterm.broadcast.APPEND_TO_PATH";
    private static final String ACTION_PATH_PREPEND_BROADCAST = "com.offsec.nhterm.broadcast.PREPEND_TO_PATH";
    private static final String PERMISSION_PATH_BROADCAST = "com.offsec.nhterm.permission.APPEND_TO_PATH";
    private static final String PERMISSION_PATH_PREPEND_BROADCAST = "com.offsec.nhterm.permission.PREPEND_TO_PATH";
    private int mPendingPathBroadcasts = 0;

    private Integer selectedTab;
    private Integer oldLength;
    AlertDialog.Builder alertDialogBuilder;
    AlertDialog alertDialog = null;
    private BroadcastReceiver mPathReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String path = makePathFromBundle(getResultExtras(false));
            if (intent.getAction().equals(ACTION_PATH_PREPEND_BROADCAST)) {
                mSettings.setPrependPath(path);
            } else {
                mSettings.setAppendPath(path);
            }
            mPendingPathBroadcasts--;

            if (mPendingPathBroadcasts <= 0 && mTermService != null) {
                populateViewFlipper();
                populateWindowList();
            }
        }
    };
    // Available on API 12 and later
    private static final int FLAG_INCLUDE_STOPPED_PACKAGES = 0x20;

    private TermService mTermService;
    private ServiceConnection mTSConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            Log.i(TermDebug.LOG_TAG, "Bound to TermService");
            TermService.TSBinder binder = (TermService.TSBinder) service;
            mTermService = binder.getService();
            if (mPendingPathBroadcasts <= 0) {
                    populateViewFlipper();
                    populateWindowList();
            }
        }

        public void onServiceDisconnected(ComponentName arg0) {
            mTermService = null;
            Log.d("onServiceDisconnected","onServiceDisconnected");
        }
    };

    private ActionBarCompat mActionBar;
    private int mActionBarMode = TermSettings.ACTION_BAR_MODE_NONE;

    private WindowListAdapter mWinListAdapter;
    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String s) {
        mSettings.readPrefs(sharedPreferences);
    }

    private class WindowListActionBarAdapter extends WindowListAdapter implements UpdateCallback {
        // From android.R.style in API 13
        private static final int TextAppearance_Holo_Widget_ActionBar_Title = 0x01030112;

        WindowListActionBarAdapter(SessionList sessions) {
            super(sessions);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Log.d("new getView", String.valueOf(position));
            Log.d("mPendingPathBroadcasts","Tamano = " + mTermService.getSessions().size());
            Log.d("mPendingPathBroadcasts","Tamano = " + oldLength);
            TextView label = new TextView(Term.this);
            @SuppressLint("StringFormatInvalid") String title = getSessionTitle(position, getString(R.string.window_title, position + 1));
            label.setText(title);
            if (AndroidCompat.SDK >= 13) {
                label.setTextAppearance(Term.this, TextAppearance_Holo_Widget_ActionBar_Title);
            } else {
                label.setTextAppearance(Term.this, android.R.style.TextAppearance_Medium);
            }
            return label;
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            Log.d("new getDropDownView", String.valueOf(position));
            return super.getView(position, convertView, parent);
        }

        public void onUpdate() {
            Log.d("onUpdate", String.valueOf(mViewFlipper.getDisplayedChild()));
            mActionBar.setSelectedNavigationItem(mViewFlipper.getDisplayedChild());
            notifyDataSetChanged();
        }
    }

    private ActionBarCompat.OnNavigationListener mWinListItemSelected = new ActionBarCompat.OnNavigationListener() {
        public boolean onNavigationItemSelected(int position, long id) {
            Log.d("mWinListItemSelected", String.valueOf(mViewFlipper.getDisplayedChild()));
            if(alertDialog != null){
                alertDialog.dismiss();
                alertDialog = null;
            }
            int oldPosition = mViewFlipper.getDisplayedChild();
            if (position != oldPosition) {
                if (position >= mViewFlipper.getChildCount()) {
                    mViewFlipper.addView(createEmulatorView(mTermSessions.get(position)));
                    Log.d("addView cc", String.valueOf(mTermSessions.get(position)));
                }
                selectedTab = position;
                oldLength = mTermSessions.size();
                mTermSessions.setOldSize(oldLength);
                mTermSessions.setSelectedSession(selectedTab);
                mViewFlipper.setDisplayedChild(selectedTab);
                if (mActionBarMode == TermSettings.ACTION_BAR_MODE_HIDES) {
                    mActionBar.hide();
                }
            }
            selectedTab = position;
            oldLength = mTermSessions.size();
            mTermSessions.setOldSize(oldLength);
            mTermSessions.setSelectedSession(selectedTab);
            mViewFlipper.setDisplayedChild(selectedTab);
            return true;
        }
    };

    private boolean mHaveFullHwKeyboard = false;

    private class EmulatorViewGestureListener extends SimpleOnGestureListener {
        private EmulatorView view;

        public EmulatorViewGestureListener(EmulatorView view) {
            this.view = view;
        }

        @Override
        public boolean onSingleTapUp(MotionEvent e) {
            // Let the EmulatorView handle taps if mouse tracking is active
            if (view.isMouseTrackingActive()) return false;

            //Check for link at tap location
            String link = view.getURLat(e.getX(), e.getY());
            if(link != null)
                execURL(link);
            else
                doUIToggle((int) e.getX(), (int) e.getY(), view.getVisibleWidth(), view.getVisibleHeight());
            return true;
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            float absVelocityX = Math.abs(velocityX);
            float absVelocityY = Math.abs(velocityY);
            if (absVelocityX > Math.max(1000.0f, 2.0 * absVelocityY)) {
                // Assume user wanted side to side movement
                if (velocityX > 0) {
                    // Left to right swipe -- previous window
                    mViewFlipper.showPrevious();
                } else {
                    // Right to left swipe -- next window
                    mViewFlipper.showNext();
                }
                return true;
            } else {
                return false;
            }
        }
    }

    /**
     * Should we use keyboard shortcuts?
     */
    private boolean mUseKeyboardShortcuts;

    /**
     * Intercepts keys before the view/terminal gets it.
     */
    private View.OnKeyListener mKeyListener = new View.OnKeyListener() {
        public boolean onKey(View v, int keyCode, KeyEvent event) {
            return backkeyInterceptor(keyCode, event) || keyboardShortcuts(keyCode, event);
        }

        /**
         * Keyboard shortcuts (tab management, paste)
         */
        private boolean keyboardShortcuts(int keyCode, KeyEvent event) {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return false;
            }
            if (!mUseKeyboardShortcuts) {
                return false;
            }
            boolean isCtrlPressed = (event.getMetaState() & KeycodeConstants.META_CTRL_ON) != 0;
            boolean isShiftPressed = (event.getMetaState() & KeycodeConstants.META_SHIFT_ON) != 0;

            if (keyCode == KeycodeConstants.KEYCODE_TAB && isCtrlPressed) {
                if (isShiftPressed) {
                    mViewFlipper.showPrevious();
                } else {
                    mViewFlipper.showNext();
                }

                return true;
            } else if (keyCode == KeycodeConstants.KEYCODE_N && isCtrlPressed && isShiftPressed) {
                doCreateNewWindow();

                return true;
            } else if (keyCode == KeycodeConstants.KEYCODE_V && isCtrlPressed && isShiftPressed) {
                doPaste();

                return true;
            } else {
                return false;
            }
        }

        /**
         * Make sure the back button always leaves the application.
         */
        private boolean backkeyInterceptor(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_BACK && mActionBarMode == TermSettings.ACTION_BAR_MODE_HIDES && mActionBar != null && mActionBar.isShowing()) {
                /* We need to intercept the key event before the view sees it,
                   otherwise the view will handle it before we get it */
                onKeyUp(keyCode, event);
                return true;
            } else {
                return false;
            }
        }
    };

    private Handler mHandler = new Handler();

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        Log.v(TermDebug.LOG_TAG, "onCreate");

        mPrivateAlias = new ComponentName(this, RemoteInterface.PRIVACT_ACTIVITY_ALIAS);

        if (icicle == null)
            onNewIntent(getIntent());

        final SharedPreferences mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        mSettings = new TermSettings(getResources(), mPrefs);
        mPrefs.registerOnSharedPreferenceChangeListener(this);

        Intent broadcast = new Intent(ACTION_PATH_BROADCAST);
        if (AndroidCompat.SDK >= 12) {
            broadcast.addFlags(FLAG_INCLUDE_STOPPED_PACKAGES);
        }
        mPendingPathBroadcasts++;
        sendOrderedBroadcast(broadcast, PERMISSION_PATH_BROADCAST, mPathReceiver, null, RESULT_OK, null, null);

        broadcast = new Intent(broadcast);
        broadcast.setAction(ACTION_PATH_PREPEND_BROADCAST);
        mPendingPathBroadcasts++;
        sendOrderedBroadcast(broadcast, PERMISSION_PATH_PREPEND_BROADCAST, mPathReceiver, null, RESULT_OK, null, null);

        TSIntent = new Intent(this, TermService.class);
        startService(TSIntent);

        if (AndroidCompat.SDK >= 11) {
            int actionBarMode = mSettings.actionBarMode();
            mActionBarMode = actionBarMode;
            if (AndroidCompat.V11ToV20) {
                switch (actionBarMode) {
                case TermSettings.ACTION_BAR_MODE_ALWAYS_VISIBLE:
                    setTheme(R.style.Theme_Holo);
                    break;
                case TermSettings.ACTION_BAR_MODE_HIDES:
                    setTheme(R.style.Theme_Holo_ActionBarOverlay);
                    break;
                }
            }
        } else {
            mActionBarMode = TermSettings.ACTION_BAR_MODE_ALWAYS_VISIBLE;
        }

        setContentView(R.layout.term_activity);
        mViewFlipper = (TermViewFlipper) findViewById(VIEW_FLIPPER);
        setFunctionKeyListener();

        PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TermDebug.LOG_TAG);
        WifiManager wm = (WifiManager)getSystemService(Context.WIFI_SERVICE);
        int wifiLockMode = WifiManager.WIFI_MODE_FULL;
        if (AndroidCompat.SDK >= 12) {
            wifiLockMode = WIFI_MODE_FULL_HIGH_PERF;
        }
        mWifiLock = wm.createWifiLock(wifiLockMode, TermDebug.LOG_TAG);

        ActionBarCompat actionBar = ActivityCompat.getActionBar(this);
        if (actionBar != null) {
            mActionBar = actionBar;
            actionBar.setNavigationMode(ActionBarCompat.NAVIGATION_MODE_LIST);
            actionBar.setDisplayOptions(0, ActionBarCompat.DISPLAY_SHOW_TITLE);
            if (mActionBarMode == TermSettings.ACTION_BAR_MODE_HIDES) {
                actionBar.hide();
            }
        }

        mHaveFullHwKeyboard = checkHaveFullHwKeyboard(getResources().getConfiguration());

        if (mFunctionBar == -1) mFunctionBar = mSettings.showFunctionBar() ? 1 : 0;
        if (mFunctionBar == 0) setFunctionBar(mFunctionBar);

        updatePrefs();
        mAlreadyStarted = true;
    }

    private String makePathFromBundle(Bundle extras) {
        if (extras == null || extras.size() == 0) {
            return "";
        }

        String[] keys = new String[extras.size()];
        keys = extras.keySet().toArray(keys);
        Collator collator = Collator.getInstance(Locale.US);
        Arrays.sort(keys, collator);

        StringBuilder path = new StringBuilder();
        for (String key : keys) {
            String dir = extras.getString(key);
            if (dir != null && !dir.equals("")) {
                path.append(dir);
                path.append(":");
            }
        }

        return path.substring(0, path.length()-1);
    }

    @Override
    protected void onStart() {
        super.onStart();

        if (!bindService(TSIntent, mTSConnection, BIND_AUTO_CREATE)) {
            throw new IllegalStateException("Failed to bind to TermService!");
        }
    }
    private void end_populateViewFlipper(){

        mTermSessions.addCallback(this);
        for (TermSession _session : mTermSessions) {
            EmulatorView view = createEmulatorView(_session);
            mViewFlipper.addView(view);
        }
        updatePrefs();
        if (onResumeSelectWindow >= 0) {
            //mViewFlipper.setDisplayedChild(onResumeSelectWindow);
            onResumeSelectWindow = -1;
        }
        mViewFlipper.onResume();
    }
    private void populateViewFlipper() {

        if (mTermService != null) {
            mTermSessions = mTermService.getSessions();

            if (mTermSessions.size() == 0) {
                show_shell_dialog("populateViewFlipper");

            } else{
                end_populateViewFlipper();
            }


        }
    }

    private void populateWindowList() {
        if (mActionBar == null) {
            Log.d("populateWindowList", "in null");
            return;
        }
        if (mTermSessions != null) {
            Log.d("populateWindowList", "in Not null");
            int position = mViewFlipper.getDisplayedChild();
            Integer curLength;
            if (mWinListAdapter == null) {
                mWinListAdapter = new WindowListActionBarAdapter(mTermSessions);
                Log.d("populateWindowList", "in mWinListAdapter = null");
                mActionBar.setListNavigationCallbacks(mWinListAdapter, mWinListItemSelected);
                //mActionBar.setSelectedNavigationItem(position);
                // POC sometimes not workin?
                if(mTermSessions.getSelectedSession() == 0){
                    Log.d("populateWindowList", "curLength  == null");
                    selectedTab = 0;
                    curLength = 1;
                    oldLength = 1;
                } else {
                    if(mTermSessions.size() > mTermSessions.getOldSize()){
                        //added 1
                        curLength = mTermSessions.size();
                        selectedTab = curLength -1;
                        mTermSessions.setSelectedSession(selectedTab);
                        mTermSessions.setOldSize(curLength);
                        oldLength = curLength;

                        Log.d("populateWindowList","added 1");
                        mWinListAdapter.setSessions(mTermSessions);
                        mActionBar.setSelectedNavigationItem(selectedTab);
                        mViewFlipper.addCallback(mWinListAdapter);
                    } else  {
                        selectedTab = mTermSessions.getSelectedSession();
                        curLength = mTermSessions.size();
                        oldLength = mTermSessions.getOldSize();
                    }

                }
                mActionBar.setSelectedNavigationItem(selectedTab);
                mViewFlipper.addCallback(mWinListAdapter);

            } else {
                curLength = mWinListAdapter.getCount();
                if(curLength > oldLength){
                    //added 1
                    Log.d("cur: " + curLength, "last: " + oldLength);
                    selectedTab = curLength -1;
                    mTermSessions.setSelectedSession(selectedTab);
                    mTermSessions.setOldSize(curLength);
                    oldLength = curLength;

                    Log.d("populateWindowList","added 1");
                    mWinListAdapter.setSessions(mTermSessions);
                    mActionBar.setSelectedNavigationItem(selectedTab);
                    mViewFlipper.addCallback(mWinListAdapter);
                } else{
                    Log.d("populateWindowList","in selectedTab");
                    mWinListAdapter.setSessions(mTermSessions);
                    mActionBar.setSelectedNavigationItem(selectedTab);
                    mTermSessions.setSelectedSession(selectedTab);
                    mViewFlipper.addCallback(mWinListAdapter);
                }


            }



        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        PreferenceManager.getDefaultSharedPreferences(this)
                .unregisterOnSharedPreferenceChangeListener(this);

        if (mStopServiceOnFinish) {
            stopService(TSIntent);
            mFunctionBar = -1;
        }
        mTermService = null;
        mTSConnection = null;
        if (mWakeLock.isHeld()) {
            mWakeLock.release();
        }
        if (mWifiLock.isHeld()) {
            mWifiLock.release();
        }
    }

    private void restart() {
        startActivity(getIntent());
        finish();
    }

    protected static TermSession createTermSession(Context context, TermSettings settings, String initialCommand, String _mShell) throws IOException {



        Log.d("MM createTermSession", _mShell + "cmd: " + initialCommand);
        GenericTermSession session = new ShellTermSession(settings, initialCommand, _mShell);  // called from intents
        // XXX We should really be able to fetch this from within TermSession

        session.setProcessExitMessage(context.getString(R.string.process_exit_message));

        return session;
    }

    private TermSession createTermSession() throws IOException {
        Log.d("MM createTermSession", "inthreow");
        TermSettings settings = mSettings;
        TermSession session = createTermSession(this, settings, "", "/system/bin/sh -");
        session.setFinishCallback(mTermService);
        return session;
    }

    private TermView createEmulatorView(TermSession session) {
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);
        TermView emulatorView = new TermView(this, session, metrics);

        emulatorView.setExtGestureListener(new EmulatorViewGestureListener(emulatorView));
        emulatorView.setOnKeyListener(mKeyListener);
        registerForContextMenu(emulatorView);

        return emulatorView;
    }

    private TermSession getCurrentTermSession() {
        SessionList sessions = mTermSessions;
        if (sessions == null) {
            return null;
        } else {
            return sessions.get(mViewFlipper.getDisplayedChild());
        }
    }

    private EmulatorView getCurrentEmulatorView() {
        return (EmulatorView) mViewFlipper.getCurrentView();
    }

    private void updatePrefs() {
        mUseKeyboardShortcuts = mSettings.getUseKeyboardShortcutsFlag();

        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);

        setFunctionKeyVisibility();
        mViewFlipper.updatePrefs(mSettings);

        for (View v : mViewFlipper) {
            ((EmulatorView) v).setDensity(metrics);
            ((TermView) v).updatePrefs(mSettings);
        }

        if (mTermSessions != null) {
            for (TermSession session : mTermSessions) {
                ((GenericTermSession) session).updatePrefs(mSettings);
            }
        }

        {
            Window win = getWindow();
            WindowManager.LayoutParams params = win.getAttributes();
            final int FULLSCREEN = WindowManager.LayoutParams.FLAG_FULLSCREEN;
            int desiredFlag = mSettings.showStatusBar() ? 0 : FULLSCREEN;
            if (desiredFlag != (params.flags & FULLSCREEN) || (AndroidCompat.SDK >= 11 && mActionBarMode != mSettings.actionBarMode())) {
                if (mAlreadyStarted) {
                    // Can't switch to/from fullscreen after
                    // starting the activity.
                    restart();
                } else {
                    win.setFlags(desiredFlag, FULLSCREEN);
                    if (mActionBarMode == TermSettings.ACTION_BAR_MODE_HIDES) {
                        if (mActionBar != null) {
                            mActionBar.hide();
                        }
                    }
                }
            }
        }

        int orientation = mSettings.getScreenOrientation();
        int o = 0;
        if (orientation == 0) {
            o = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        } else if (orientation == 1) {
            o = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        } else if (orientation == 2) {
            o = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        } else {
            /* Shouldn't be happened. */
        }
        setRequestedOrientation(o);
    }

    @Override
    public void onPause() {
        super.onPause();

        if (AndroidCompat.SDK < 5) {
            /* If we lose focus between a back key down and a back key up,
               we shouldn't respond to the next back key up event unless
               we get another key down first */
            mBackKeyPressed = false;
        }

        /* Explicitly close the input method
           Otherwise, the soft keyboard could cover up whatever activity takes
           our place */
        final IBinder token = mViewFlipper.getWindowToken();
        new Thread() {
            @Override
            public void run() {
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(token, 0);
            }
        }.start();
    }

    @Override
    protected void onStop() {
        mViewFlipper.onPause();
        if (mTermSessions != null) {
            mTermSessions.removeCallback(this);

            if (mWinListAdapter != null) {
                mTermSessions.removeCallback(mWinListAdapter);
                mTermSessions.removeTitleChangedListener(mWinListAdapter);
                mViewFlipper.removeCallback(mWinListAdapter);
            }
        }

        mViewFlipper.removeAllViews();

        unbindService(mTSConnection);

        super.onStop();
    }

    private boolean checkHaveFullHwKeyboard(Configuration c) {
        return (c.keyboard == Configuration.KEYBOARD_QWERTY) &&
            (c.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        mHaveFullHwKeyboard = checkHaveFullHwKeyboard(newConfig);

        EmulatorView v = (EmulatorView) mViewFlipper.getCurrentView();
        if (v != null) {
            v.updateSize(false);
            v.onConfigurationChangedToEmulatorView(newConfig);
        }

        if (mWinListAdapter != null) {
            // Force Android to redraw the label in the navigation dropdown
            mWinListAdapter.notifyDataSetChanged();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        MenuItemCompat.setShowAsAction(menu.findItem(R.id.menu_new_window), MenuItemCompat.SHOW_AS_ACTION_ALWAYS);
        MenuItemCompat.setShowAsAction(menu.findItem(R.id.menu_close_window), MenuItemCompat.SHOW_AS_ACTION_IF_ROOM);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_preferences) {
            doPreferences();
        } else if (id == R.id.menu_new_window) {
            doCreateNewWindow();
        } else if (id == R.id.menu_close_window) {
            confirmCloseWindow();
        } else if (id == R.id.menu_window_list) {
            startActivityForResult(new Intent(this, WindowList.class), REQUEST_CHOOSE_WINDOW);
        } else if (id == R.id.menu_reset) {
            doResetTerminal();
            Toast toast = Toast.makeText(this,R.string.reset_toast_notification,Toast.LENGTH_LONG);
            toast.setGravity(Gravity.CENTER, 0, 0);
            toast.show();
        } else if (id == R.id.menu_send_email) {
            doEmailTranscript();
        } else if (id == R.id.menu_special_keys) {
            doDocumentKeys();
        } else if (id == R.id.menu_toggle_soft_keyboard) {
            doToggleSoftKeyboard();
        } else if (id == R.id.menu_toggle_wakelock) {
            doToggleWakeLock();
        } else if (id == R.id.menu_toggle_wifilock) {
            doToggleWifiLock();
        } else if  (id == R.id.action_help) {
                Intent openHelp = new Intent(Intent.ACTION_VIEW,
                Uri.parse(getString(R.string.help_url)));
                startActivity(openHelp);
        }
        // Hide the action bar if appropriate
        if (mActionBarMode == TermSettings.ACTION_BAR_MODE_HIDES) {
            mActionBar.hide();
        }
        return super.onOptionsItemSelected(item);
    }

    private void show_nosupersu(){
        AlertDialog.Builder builder1 = new AlertDialog.Builder(this);
        builder1.setMessage("No SU binary found!  Missing root!");
        builder1.setCancelable(true);

        builder1.setPositiveButton(
                "Ok",
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        dialog.cancel();
                    }
                });

        AlertDialog alert11 = builder1.create();
        alert11.show();
    }

    private void show_shell_dialog(final String from){
        Log.d("doCreateWin", "creating");
        final TermSettings settings = mSettings;
        if(alertDialog != null){
            alertDialog.dismiss();
            alertDialog = null;
        }
        alertDialogBuilder = new AlertDialog.Builder(this);
        //alertDialogBuilder.setView(promptsView);
        //alertDialogBuilder.setCancelable(false);
        alertDialogBuilder.setTitle("Select shell:");
        alertDialogBuilder.setNegativeButton("Android",
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        Log.d("CANCELED", "CANCELED");
                        TermSession session = null;
                        try {
                            session = createTermSession(getBaseContext(), settings, "", ShellType.ANDROID_SHELL);
                            session.setFinishCallback(mTermService);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        mTermSessions.add(session);
                        if(from == "doCreateNewWindow"){
                            end_doCreateNewWindow(session);
                        }
                        if(from ==  "populateViewFlipper"){
                            end_populateViewFlipper();
                        }

                    }
                })
                .setPositiveButton("AndroidSu",
                        new DialogInterface.OnClickListener() {
                            @TargetApi(Build.VERSION_CODES.KITKAT)
                            public void onClick(DialogInterface dialog, int id) {
                                Log.d("Su", "Su");
                                TermSession session = null;

                                if(CheckRoot.isDeviceRooted()){
                                    Log.d("isDeviceRooted","Device is rooted!");
                                try {
                                    session = createTermSession(getBaseContext(), settings, "", ShellType.ANDROID_SU_SHELL);
                                    session.setFinishCallback(mTermService);
                                } catch (IOException e) {
                                    e.printStackTrace();
                                    }
                                mTermSessions.add(session);
                                if(Objects.equals(from, "doCreateNewWindow")){
                                    end_doCreateNewWindow(session);
                                    }
                                if(Objects.equals(from, "populateViewFlipper")){
                                    end_populateViewFlipper();
                                    }

                                } else {
                                // ALERT! WHY YOU NO ROOT!
                                    Log.d("isDeviceRooted","Device is not rooted!");
                                    show_nosupersu();
                                }
                            }
                        })
                .setNeutralButton("Kali",
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                Log.d("Kali", "Kali");

                                if(CheckRoot.isDeviceRooted()){
                                    Log.d("isDeviceRooted","Device is rooted!");

                                String chroot_dir = "/data/local/nhsystem/kali-armhf"; // Not sure if I can wildcard this

                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                                        if (!dir_exists(chroot_dir)){
                                            NotFound(chroot_dir);
                                        } else {
                                            TermSession session = null;
                                            try {
                                                session = createTermSession(getBaseContext(), settings, "", ShellType.KALI_LOGIN_SHELL);
                                                session.setFinishCallback(mTermService);
                                            } catch (IOException e) {
                                                e.printStackTrace();
                                            }
                                            mTermSessions.add(session);
                                            if (from.equals("doCreateNewWindow")) {
                                                end_doCreateNewWindow(session);
                                            }
                                            if (from.equals("populateViewFlipper")) {
                                                end_populateViewFlipper();
                                            }
                                        }
                                    }
                                } else {
                                    // ALERT! WHY YOU NO ROOT!
                                    Log.d("isDeviceRooted","Device is not rooted!");
                                    show_nosupersu();
                                }
                            }
                        });

        alertDialog = alertDialogBuilder.create();
        alertDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialog) {
                Log.d("Oncancel", "size: " + mWinListAdapter.getCount());
                if(mWinListAdapter.getCount() == 0){
                    finish();
                }
            }
        });
        alertDialog.show();
    }

    public String RunAsRootOutput(String command) {
        String output = "";
        String line;
        try {
            Process process = Runtime.getRuntime().exec("su");
            OutputStream stdin = process.getOutputStream();
            InputStream stderr = process.getErrorStream();
            InputStream stdout = process.getInputStream();

            stdin.write((command + '\n').getBytes());
            stdin.write(("exit\n").getBytes());
            stdin.flush();
            stdin.close();

            BufferedReader br = new BufferedReader(new InputStreamReader(stdout));
            while ((line = br.readLine()) != null) {
                output = output + line;
            }
            br.close();
            br = new BufferedReader(new InputStreamReader(stderr));
            while ((line = br.readLine()) != null) {
                Log.e("Shell Error:", line);
            }
            br.close();
            process.waitFor();
            process.destroy();
        } catch (IOException e) {
            Log.d("Term.java ", "An IOException was caught: " + e.getMessage());
        } catch (InterruptedException ex) {
            Log.d("Term.java" , "An InterruptedException was caught: " + ex.getMessage());
        }
        return output;
    }


    public boolean dir_exists(String dir_path)
    {
        boolean ret = false;
        File dir = new File(dir_path);

        String command = "[ -d \""+ dir_path + "\" ] && echo 'True'";
        String output = RunAsRootOutput(command);
        Log.d("DIRCHECK RunAsRoot ", output);

        if ((dir.exists() && dir.isDirectory()) || (output.equals("True")))
            ret = true;
        return ret;
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    private void NotFound(String text){

        String msg = "";

        if (Objects.equals(text, "/data/data/com.offsec.nethunter/files/scripts/bootkali")){
            msg = "Please run Nethunter Application to generate!";
        } else if (Objects.equals(text, "/data/local/nhsystem/kali-armhf")){
            msg = "Missing chroot.  You need to install from Chroot Manager";
        }
        /// Do something for not found text (alertDialog)
        alertDialogBuilder = new AlertDialog.Builder(this);
        //alertDialogBuilder.setView(promptsView);
        //alertDialogBuilder.setCancelable(false);
        alertDialogBuilder.setTitle("Error");
        alertDialogBuilder.setMessage("Could not find:\n" + text + ":\n" + msg );
        alertDialogBuilder.setNegativeButton("OK!",
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                            // close
                            finish();
                            System.exit(0);
                        }
                    });
        // create alert dialog
        AlertDialog alertDialog = alertDialogBuilder.create();

        // show it
        alertDialog.show();
    }

    private void end_doCreateNewWindow(TermSession session){
        TermView view = createEmulatorView(session);
        view.updatePrefs(mSettings);
        mViewFlipper.addView(view);
        mViewFlipper.setDisplayedChild(mViewFlipper.getChildCount() - 1);
    }

    private static boolean mVimApp = false;
    private boolean doSendActionBarKey(EmulatorView view, int key) {
        if (key == 999) {
            // do nothing
        } else if (key == 1002) {

            doToggleSoftKeyboard();
        } else if (key == 1249) {
            doPaste();
        } else if (key == 1250) {
            doCreateNewWindow();
        } else if (key == 1251) {
            if (mVimApp && mSettings.getInitialCommand().matches("(.|\n)*(^|\n)-vim\\.app(.|\n)*") && mTermSessions.size() == 1) {
                sendKeyStrings(":confirm qa\r", true);
            } else {
                confirmCloseWindow();
            }
        } else if (key == 1252) {
            InputMethodManager imm = (InputMethodManager)
                    getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.showInputMethodPicker();
        } else if (key == 1253) {
            sendKeyStrings(":confirm qa\r", true);
        } else if (key == 1254) {
            view.sendFnKeyCode();
        } else if (key == KeycodeConstants.KEYCODE_ALT_LEFT) {
            view.sendAltKeyCode();
        } else if (key == KeycodeConstants.KEYCODE_CTRL_LEFT) {
            view.sendControlKeyCode();
        } else if (key == 1247) {
            sendKeyStrings(":", false);
        } else if (key == 1255) {
            setFunctionBar(2);
        } else if (key > 0) {
            KeyEvent event = new KeyEvent(KeyEvent.ACTION_DOWN, key);
            dispatchKeyEvent(event);
            event = new KeyEvent(KeyEvent.ACTION_UP, key);
            dispatchKeyEvent(event);
        }
        return true;
    }

    private void sendKeyStrings(String str, boolean esc) {
        TermSession session = getCurrentTermSession();
        if (session != null) {
            if (esc) {
                KeyEvent event = new KeyEvent(KeyEvent.ACTION_DOWN, KeycodeConstants.KEYCODE_ESCAPE);
                dispatchKeyEvent(event);
            }
            session.write(str);
        }
    }

    private void doCreateNewWindow() {
        if (mTermSessions == null) {
            Log.w(TermDebug.LOG_TAG, "Couldn't create new window because mTermSessions == null");
            return;
        }
        Log.d("doCreateWin", "creating");
        show_shell_dialog("doCreateNewWindow");
    }

    private void confirmCloseWindow() {
        final AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setIcon(android.R.drawable.ic_dialog_alert);
        b.setMessage(R.string.confirm_window_close_message);
        final Runnable closeWindow = new Runnable() {
            public void run() {
                doCloseWindow();
            }
        };
        b.setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
           public void onClick(DialogInterface dialog, int id) {
               dialog.dismiss();
               mHandler.post(closeWindow);
           }
        });
        b.setNegativeButton(android.R.string.no, null);
        b.show();
    }

    private void doCloseWindow() {
        if (mTermSessions == null) {
            return;
        }

        EmulatorView view = getCurrentEmulatorView();
        if (view == null) {
            return;
        }
        TermSession session = mTermSessions.remove(mViewFlipper.getDisplayedChild());
        view.onPause();
        session.finish();
        mViewFlipper.removeView(view);
        if (mTermSessions.size() != 0) {
            mViewFlipper.showNext();
        }else {
            Log.d("NOSCREENS?","?NOSCREENS??");
        }

    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        Log.d("onActivityResult?","?onActivityResult??");
        switch (request) {
        case REQUEST_CHOOSE_WINDOW:
            if (result == RESULT_OK && data != null) {
                int position = data.getIntExtra(EXTRA_WINDOW_ID, -2);
                if (position >= 0) {
                    // Switch windows after session list is in sync, not here
                    onResumeSelectWindow = position;
                } else if (position == -1) {
                    doCreateNewWindow();
                    onResumeSelectWindow = mTermSessions.size() - 1;
                }
            } else {
                // Close the activity if user closed all sessions
                // TODO the left path will be invoked when nothing happened, but this Activity was destroyed!
                if (mTermSessions == null || mTermSessions.size() == 0) {
                    Log.d("but this ?","?but this Activity was destroyed!??");
                    mStopServiceOnFinish = true;
                    finish();
                }
            }
            break;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        if ((intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) {
            // Don't repeat action if intent comes from history
            return;
        }

        String action = intent.getAction();
        if (TextUtils.isEmpty(action) || !mPrivateAlias.equals(intent.getComponent())) {
            return;
        }

        // huge number simply opens new window
        // TODO: add a way to restrict max number of windows per caller (possibly via reusing BoundSession)
        switch (action) {
            case RemoteInterface.PRIVACT_OPEN_NEW_WINDOW:
                onResumeSelectWindow = Integer.MAX_VALUE;
                break;
            case RemoteInterface.PRIVACT_SWITCH_WINDOW:
                int target = intent.getIntExtra(RemoteInterface.PRIVEXTRA_TARGET_WINDOW, -1);
                if (target >= 0) {
                    onResumeSelectWindow = target;
                }
                break;
        }
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem wakeLockItem = menu.findItem(R.id.menu_toggle_wakelock);
        MenuItem wifiLockItem = menu.findItem(R.id.menu_toggle_wifilock);
        if (mWakeLock.isHeld()) {
            wakeLockItem.setTitle(R.string.disable_wakelock);
        } else {
            wakeLockItem.setTitle(R.string.enable_wakelock);
        }
        if (mWifiLock.isHeld()) {
            wifiLockItem.setTitle(R.string.disable_wifilock);
        } else {
            wifiLockItem.setTitle(R.string.enable_wifilock);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v,
            ContextMenuInfo menuInfo) {
      super.onCreateContextMenu(menu, v, menuInfo);
      menu.setHeaderTitle(R.string.edit_text);
      menu.add(0, SELECT_TEXT_ID, 0, R.string.select_text);
      menu.add(0, COPY_ALL_ID, 0, R.string.copy_all);
      menu.add(0, PASTE_ID, 0, R.string.paste);
      menu.add(0, SEND_CONTROL_KEY_ID, 0, R.string.send_control_key);
      menu.add(0, SEND_FN_KEY_ID, 0, R.string.send_fn_key);
      if (!canPaste()) {
          menu.getItem(PASTE_ID).setEnabled(false);
      }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
          switch (item.getItemId()) {
          case SELECT_TEXT_ID:
            getCurrentEmulatorView().toggleSelectingText();
            return true;
          case COPY_ALL_ID:
            doCopyAll();
            return true;
          case PASTE_ID:
            doPaste();
            return true;
          case SEND_CONTROL_KEY_ID:
            doSendControlKey();
            return true;
          case SEND_FN_KEY_ID:
            doSendFnKey();
            return true;
          default:
            return super.onContextItemSelected(item);
          }
        }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        /* The pre-Eclair default implementation of onKeyDown() would prevent
           our handling of the Back key in onKeyUp() from taking effect, so
           ignore it here */
        if (AndroidCompat.SDK < 5 && keyCode == KeyEvent.KEYCODE_BACK) {
            /* Android pre-Eclair has no key event tracking, and a back key
               down event delivered to an activity above us in the back stack
               could be succeeded by a back key up event to us, so we need to
               keep track of our own back key presses */
            mBackKeyPressed = true;
            return true;
        } else {
            return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch (keyCode) {
        case KeyEvent.KEYCODE_BACK:
            if (AndroidCompat.SDK < 5) {
                if (!mBackKeyPressed) {
                    /* This key up event might correspond to a key down
                       delivered to another activity -- ignore */
                    return false;
                }
                mBackKeyPressed = false;
            }
            if (mActionBarMode == TermSettings.ACTION_BAR_MODE_HIDES && mActionBar != null && mActionBar.isShowing()) {
                mActionBar.hide();
                return true;
            }
            switch (mSettings.getBackKeyAction()) {
            case TermSettings.BACK_KEY_STOPS_SERVICE:
                mStopServiceOnFinish = true;
            case TermSettings.BACK_KEY_CLOSES_ACTIVITY:
                finish();
                return true;
            case TermSettings.BACK_KEY_CLOSES_WINDOW:
                doCloseWindow();
                return true;
            case TermSettings.BACK_KEY_TOGGLE_IME:
                doToggleSoftKeyboard();
                return true;
            default:
                return false;
            }
        case KeyEvent.KEYCODE_MENU:
            if (mActionBar != null && !mActionBar.isShowing()) {
                mActionBar.show();
                return true;
            } else {
                return super.onKeyUp(keyCode, event);
            }
        default:
            return super.onKeyUp(keyCode, event);
        }
    }

    // Called when the list of sessions changes
    public void onUpdate() {
        SessionList sessions = mTermSessions;
        if (sessions == null) {
            Log.d("onupdateeeeee","sessions == null");
            return;
        }

        if (sessions.size() == 0) {
            Log.d("onupdateeeeee","tamano 0");
            mStopServiceOnFinish = true;
            finish();
        } else if (sessions.size() < mViewFlipper.getChildCount()) {
            Log.d("onupdateeeeee",sessions.size() + "sessions en el if " + mViewFlipper.getChildCount());
            for (int i = 0; i < mViewFlipper.getChildCount(); ++i) {
                EmulatorView v = (EmulatorView) mViewFlipper.getChildAt(i);
                if (!sessions.contains(v.getTermSession())) {
                    v.onPause();
                    mViewFlipper.removeView(v);
                    --i;
                }
            }
        }
    }

    private boolean canPaste() {
        ClipboardManagerCompat clip = ClipboardManagerCompatFactory
                .getManager(getApplicationContext());
        return clip.hasText();
    }

    private void doPreferences() {
        startActivity(new Intent(this, TermPreferences.class));
    }

    private void doResetTerminal() {
        TermSession session = getCurrentTermSession();
        if (session != null) {
            session.reset();
        }
    }

    private void doEmailTranscript() {
        TermSession session = getCurrentTermSession();
        if (session != null) {
            // Don't really want to supply an address, but
            // currently it's required, otherwise nobody
            // wants to handle the intent.
            String addr = "user@example.com";
            Intent intent =
                    new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"
                            + addr));

            String subject = getString(R.string.email_transcript_subject);
            String title = session.getTitle();
            if (title != null) {
                subject = subject + " - " + title;
            }
            intent.putExtra(Intent.EXTRA_SUBJECT, subject);
            intent.putExtra(Intent.EXTRA_TEXT,
                    session.getTranscriptText().trim());
            try {
                startActivity(Intent.createChooser(intent,
                        getString(R.string.email_transcript_chooser_title)));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this,
                        R.string.email_transcript_no_email_activity_found,
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void doCopyAll() {
        ClipboardManagerCompat clip = ClipboardManagerCompatFactory
                .getManager(getApplicationContext());
        clip.setText(getCurrentTermSession().getTranscriptText().trim());
    }

    private void doPaste() {
        if (!canPaste()) {
            return;
        }
        ClipboardManagerCompat clip = ClipboardManagerCompatFactory
                .getManager(getApplicationContext());
        CharSequence paste = clip.getText();
        getCurrentTermSession().write(paste.toString());
    }

    private void doSendControlKey() {
        getCurrentEmulatorView().sendControlKey();
    }

    private void doSendFnKey() {
        getCurrentEmulatorView().sendFnKey();
    }

    private void doDocumentKeys() {
        AlertDialog.Builder dialog = new AlertDialog.Builder(this);
        Resources r = getResources();
        dialog.setTitle(r.getString(R.string.control_key_dialog_title));
        dialog.setMessage(
            formatMessage(mSettings.getControlKeyId(), TermSettings.CONTROL_KEY_ID_NONE,
                r, R.array.control_keys_short_names,
                R.string.control_key_dialog_control_text,
                R.string.control_key_dialog_control_disabled_text, "CTRLKEY")
            + "\n\n" +
            formatMessage(mSettings.getFnKeyId(), TermSettings.FN_KEY_ID_NONE,
                r, R.array.fn_keys_short_names,
                R.string.control_key_dialog_fn_text,
                R.string.control_key_dialog_fn_disabled_text, "FNKEY"));
         dialog.show();
     }

     private String formatMessage(int keyId, int disabledKeyId,
         Resources r, int arrayId,
         int enabledId,
         int disabledId, String regex) {
         if (keyId == disabledKeyId) {
             return r.getString(disabledId);
         }
         String[] keyNames = r.getStringArray(arrayId);
         String keyName = keyNames[keyId];
         String template = r.getString(enabledId);
         String result = template.replaceAll(regex, keyName);
         return result;
    }

    private void doToggleSoftKeyboard() {
        InputMethodManager imm = (InputMethodManager)
            getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.toggleSoftInput(InputMethodManager.SHOW_FORCED,0);

    }

    private void doToggleWakeLock() {
        if (mWakeLock.isHeld()) {
            mWakeLock.release();
        } else {
            mWakeLock.acquire();
        }
        ActivityCompat.invalidateOptionsMenu(this);
    }

    private void doToggleWifiLock() {
        if (mWifiLock.isHeld()) {
            mWifiLock.release();
        } else {
            mWifiLock.acquire();
        }
        ActivityCompat.invalidateOptionsMenu(this);
    }

    private void doToggleActionBar() {
        ActionBarCompat bar = mActionBar;
        if (bar == null) {
            return;
        }
        if (bar.isShowing()) {
            bar.hide();
        } else {
            bar.show();
        }
    }

    private void doUIToggle(int x, int y, int width, int height) {
        switch (mActionBarMode) {
        case TermSettings.ACTION_BAR_MODE_NONE:
            if (AndroidCompat.SDK >= 11 && (mHaveFullHwKeyboard || y < height / 2)) {
                openOptionsMenu();
                return;
            } else {
                doToggleSoftKeyboard();
            }
            break;
        case TermSettings.ACTION_BAR_MODE_ALWAYS_VISIBLE:
            if (!mHaveFullHwKeyboard) {
                doToggleSoftKeyboard();
            }
            break;
        case TermSettings.ACTION_BAR_MODE_HIDES:
            if (mHaveFullHwKeyboard || y < height / 2) {
                doToggleActionBar();
                return;
            } else {
                doToggleSoftKeyboard();
            }
            break;
        }
        getCurrentEmulatorView().requestFocus();
    }

    /**
     *
     * Send a URL up to Android to be handled by a browser.
     * @param link The URL to be opened.
     */
    private void execURL(String link)
    {
        Uri webLink = Uri.parse(link);
        Intent openLink = new Intent(Intent.ACTION_VIEW, webLink);
        PackageManager pm = getPackageManager();
        List<ResolveInfo> handlers = pm.queryIntentActivities(openLink, 0);
        if(handlers.size() > 0)
            startActivity(openLink);
    }
    private static int mFunctionBar = -1;

    private void setFunctionBar(int mode) {
        if (mode == 2) mFunctionBar = mFunctionBar == 0 ? 1 : 0;
        else mFunctionBar = mode;
        if (mAlreadyStarted) updatePrefs();
    }

    private void setFunctionBarSize() {
        int size = findViewById(R.id.view_function_bar).getHeight();
        if (mViewFlipper != null) mViewFlipper.setFunctionBarSize(size);
    }

    private void setFunctionKeyListener() {
        findViewById(R.id.button_esc  ).setOnClickListener(this);
        findViewById(R.id.button_ctrl ).setOnClickListener(this);
        findViewById(R.id.button_alt ).setOnClickListener(this);
        findViewById(R.id.button_tab  ).setOnClickListener(this);
        findViewById(R.id.button_up   ).setOnClickListener(this);
        findViewById(R.id.button_down ).setOnClickListener(this);
        findViewById(R.id.button_left ).setOnClickListener(this);
        findViewById(R.id.button_right).setOnClickListener(this);
        findViewById(R.id.button_backspace).setOnClickListener(this);
        findViewById(R.id.button_enter).setOnClickListener(this);
        findViewById(R.id.button_i).setOnClickListener(this);
        findViewById(R.id.button_colon).setOnClickListener(this);
        findViewById(R.id.button_slash).setOnClickListener(this);
        findViewById(R.id.button_equal).setOnClickListener(this);
        findViewById(R.id.button_asterisk).setOnClickListener(this);
        findViewById(R.id.button_pipe).setOnClickListener(this);
        findViewById(R.id.button_minus).setOnClickListener(this);
        findViewById(R.id.button_vim_paste).setOnClickListener(this);
        findViewById(R.id.button_vim_yank).setOnClickListener(this);
        findViewById(R.id.button_softkeyboard).setOnClickListener(this);
        findViewById(R.id.button_menu).setOnClickListener(this);
        findViewById(R.id.button_menu_hide).setOnClickListener(this);
        findViewById(R.id.button_menu_plus ).setOnClickListener(this);
        findViewById(R.id.button_menu_minus).setOnClickListener(this);
        findViewById(R.id.button_menu_x    ).setOnClickListener(this);
        findViewById(R.id.button_menu_user ).setOnClickListener(this);
        findViewById(R.id.button_menu_quit ).setOnClickListener(this);
    }

    private void setFunctionKeyVisibility() {
        int visibility;
        final SharedPreferences mPrefs = PreferenceManager.getDefaultSharedPreferences(this);

        visibility = mPrefs.getBoolean("functionbar_esc", true) ? View.VISIBLE : View.GONE;
        setFunctionBarButton(R.id.button_esc, visibility);
        visibility = mPrefs.getBoolean("functionbar_ctrl", true) ? View.VISIBLE : View.GONE;
        setFunctionBarButton(R.id.button_ctrl, visibility);
        visibility = mPrefs.getBoolean("functionbar_alt", true) ? View.VISIBLE : View.GONE;
        setFunctionBarButton(R.id.button_alt, visibility);
        visibility = mPrefs.getBoolean("functionbar_tab", true) ? View.VISIBLE : View.GONE;
        setFunctionBarButton(R.id.button_tab, visibility);

        visibility = mPrefs.getBoolean("functionbar_up", true) ? View.VISIBLE : View.GONE;
        setFunctionBarButton(R.id.button_up, visibility);
        visibility = mPrefs.getBoolean("functionbar_down", true) ? View.VISIBLE : View.GONE;
        setFunctionBarButton(R.id.button_down, visibility);
        visibility = mPrefs.getBoolean("functionbar_left", true) ? View.VISIBLE : View.GONE;
        setFunctionBarButton(R.id.button_left, visibility);
        visibility = mPrefs.getBoolean("functionbar_right", true) ? View.VISIBLE : View.GONE;
        setFunctionBarButton(R.id.button_right, visibility);

        visibility = mPrefs.getBoolean("functionbar_backspace", false) ? View.VISIBLE : View.GONE;
        setFunctionBarButton(R.id.button_backspace, visibility);
        visibility = mPrefs.getBoolean("functionbar_enter", true) ? View.VISIBLE : View.GONE;
        setFunctionBarButton(R.id.button_enter, visibility);

        visibility = mPrefs.getBoolean("functionbar_i", false) ? View.VISIBLE : View.GONE;
        setFunctionBarButton(R.id.button_i, visibility);
        visibility = mPrefs.getBoolean("functionbar_colon", false) ? View.VISIBLE : View.GONE;
        setFunctionBarButton(R.id.button_colon, visibility);
        visibility = mPrefs.getBoolean("functionbar_slash", true) ? View.VISIBLE : View.GONE;
        setFunctionBarButton(R.id.button_slash, visibility);
        visibility = mPrefs.getBoolean("functionbar_equal", false) ? View.VISIBLE : View.GONE;
        setFunctionBarButton(R.id.button_equal, visibility);
        visibility = mPrefs.getBoolean("functionbar_asterisk", false) ? View.VISIBLE : View.GONE;
        setFunctionBarButton(R.id.button_asterisk, visibility);
        visibility = mPrefs.getBoolean("functionbar_pipe", true) ? View.VISIBLE : View.GONE;
        setFunctionBarButton(R.id.button_pipe, visibility);
        visibility = mPrefs.getBoolean("functionbar_minus", true) ? View.VISIBLE : View.GONE;
        setFunctionBarButton(R.id.button_minus, visibility);
        visibility = mPrefs.getBoolean("functionbar_vim_paste", true) ? View.VISIBLE : View.GONE;
        setFunctionBarButton(R.id.button_vim_paste, visibility);
        visibility = mPrefs.getBoolean("functionbar_vim_yank", false) ? View.VISIBLE : View.GONE;
        setFunctionBarButton(R.id.button_vim_yank, visibility);

        visibility = mPrefs.getBoolean("functionbar_menu", true) ? View.VISIBLE : View.GONE;
        setFunctionBarButton(R.id.button_menu, visibility);
        visibility = mPrefs.getBoolean("functionbar_softkeyboard", true) ? View.VISIBLE : View.GONE;
        setFunctionBarButton(R.id.button_softkeyboard, visibility);
        visibility = mPrefs.getBoolean("functionbar_hide", true) ? View.VISIBLE : View.GONE;
        setFunctionBarButton(R.id.button_menu_hide, visibility);

        visibility = View.GONE;
        // visibility = mPrefs.getBoolean("functionbar_menu_plus", false)  ? View.VISIBLE : View.GONE;
        setFunctionBarButton(R.id.button_menu_plus, visibility);
        // visibility = mPrefs.getBoolean("functionbar_menu_minus", false) ? View.VISIBLE : View.GONE;
        setFunctionBarButton(R.id.button_menu_minus, visibility);
        // visibility = mPrefs.getBoolean("functionbar_menu_x", false) ? View.VISIBLE : View.GONE;
        setFunctionBarButton(R.id.button_menu_x, visibility);
        // visibility = mPrefs.getBoolean("functionbar_menu_user", false)  ? View.VISIBLE : View.GONE;
        setFunctionBarButton(R.id.button_menu_user, visibility);
        visibility = mPrefs.getBoolean("functionbar_menu_quit", true)  ? View.VISIBLE : View.GONE;
        setFunctionBarButton(R.id.button_menu_quit, visibility);

        setFunctionBarSize();
        visibility = mFunctionBar == 1 ? View.VISIBLE : View.GONE;
        findViewById(R.id.view_function_bar).setVisibility(visibility);
        mViewFlipper.setFunctionBar(mFunctionBar == 1);
    }

    @SuppressLint("NewApi")
    private void setFunctionBarButton(int id, int visibility) {
        Button button = (Button)findViewById(id);
        button.setVisibility(visibility);
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);
        int height = mSettings.getFontSize() * (int) (metrics.density * metrics.scaledDensity);
        button.setMinHeight(height);
        if (AndroidCompat.SDK >= 14) {
            button.setAllCaps(false);
        }
    }

    public void onClick(View v) {
        EmulatorView view = getCurrentEmulatorView();
        switch (v.getId()) {
            case R.id.button_esc:
                doSendActionBarKey(view, KeycodeConstants.KEYCODE_ESCAPE);
                break;
            case R.id.button_ctrl:
                doSendActionBarKey(view, KeycodeConstants.KEYCODE_CTRL_LEFT);
                break;
            case R.id.button_alt:
                doSendActionBarKey(view, KeycodeConstants.KEYCODE_ALT_LEFT);
                break;
            case R.id.button_tab:
                doSendActionBarKey(view, KeycodeConstants.KEYCODE_TAB);
                break;
            case R.id.button_up:
                doSendActionBarKey(view, KeycodeConstants.KEYCODE_DPAD_UP);
                break;
            case R.id.button_down:
                doSendActionBarKey(view, KeycodeConstants.KEYCODE_DPAD_DOWN);
                break;
            case R.id.button_left:
                doSendActionBarKey(view, KeycodeConstants.KEYCODE_DPAD_LEFT);
                break;
            case R.id.button_right:
                doSendActionBarKey(view, KeycodeConstants.KEYCODE_DPAD_RIGHT);
                break;
            case R.id.button_backspace:
                doSendActionBarKey(view, KeycodeConstants.KEYCODE_DEL);
                break;
            case R.id.button_enter:
                doSendActionBarKey(view, KeycodeConstants.KEYCODE_ENTER);
                break;
            case R.id.button_i:
                sendKeyStrings("i", false);
                break;
            case R.id.button_colon:
                sendKeyStrings(":", false);
                break;
            case R.id.button_slash:
                sendKeyStrings("/", false);
                break;
            case R.id.button_equal:
                sendKeyStrings("=", false);
                break;
            case R.id.button_asterisk:
                sendKeyStrings("*", false);
                break;
            case R.id.button_pipe:
                sendKeyStrings("|", false);
                break;
            case R.id.button_minus:
                sendKeyStrings("-", false);
                break;
            case R.id.button_vim_paste:
                doPaste();
                //sendKeyStrings("\"*p", false);
                break;
            case R.id.button_vim_yank:
                sendKeyStrings("\"*yy", false);
                break;
            case R.id.button_menu_plus:
                doSendActionBarKey(view, mSettings.getActionBarPlusKeyAction());
                break;
            case R.id.button_menu_minus:
                doSendActionBarKey(view, mSettings.getActionBarMinusKeyAction());
                break;
            case R.id.button_menu_x:
                doSendActionBarKey(view, mSettings.getActionBarXKeyAction());
                break;
            case R.id.button_menu_user:
                doSendActionBarKey(view, mSettings.getActionBarUserKeyAction());
                break;
            case R.id.button_menu_quit:
                doSendActionBarKey(view, mSettings.getActionBarQuitKeyAction());
                break;
            case R.id.button_softkeyboard:
                doSendActionBarKey(view, mSettings.getActionBarIconKeyAction());
                break;
            case R.id.button_menu:
                openOptionsMenu();
                break;
            case R.id.button_menu_hide:
                setFunctionBar(2);
                break;
        }
    }
}

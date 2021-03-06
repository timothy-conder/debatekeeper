/*
 * Copyright (C) 2012 Phillip Cao, Chuan-Zheng Lee
 *
 * This file is part of the Debatekeeper app, which is licensed under the
 * GNU General Public Licence version 3 (GPLv3).  You can redistribute
 * and/or modify it under the terms of the GPLv3, and you must not use
 * this file except in compliance with the GPLv3.
 *
 * This app is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public Licence for more details.
 *
 * You should have received a copy of the GNU General Public Licence
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.czlee.debatekeeper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;

import net.czlee.debatekeeper.AlertManager.FlashScreenMode;
import net.czlee.debatekeeper.SpeechFormat.CountDirection;

import org.xml.sax.SAXException;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.res.Resources;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.view.GestureDetector;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;
import android.widget.ViewFlipper;


/**
 * This is the main activity for the Debatekeeper application.  It is the launcher activity,
 * and the activity in which the user spends the most time.
 *
 * @author Phillip Cao
 * @author Chuan-Zheng Lee
 * @since  2012-04-05
 *
 */
public class DebatingActivity extends Activity {

    private ViewFlipper mDebateTimerViewFlipper;
    private RelativeLayout[] mDebateTimerDisplays;
    private int mCurrentDebateTimerDisplayIndex = 0;
    private boolean mIsEditingTime = false;

    private Button mLeftControlButton;
    private Button mCentreControlButton;
    private Button mRightControlButton;
    private Button mPlayBellButton;

    private DebateManager mDebateManager;
    private Bundle mLastStateBundle;
    private FormatXmlFilesManager mFilesManager;

    private String mFormatXmlFileName = null;
    private UserPreferenceCountDirection mUserCountDirection = UserPreferenceCountDirection.GENERALLY_UP;

    private static final String BUNDLE_SUFFIX_DEBATE_MANAGER           = "dm";
    private static final String PREFERENCE_XML_FILE_NAME               = "xmlfn";
    private static final String DIALOG_BUNDLE_FATAL_MESSAGE            = "fm";
    private static final String DIALOG_BUNDLE_XML_ERROR_LOG            = "xel";

    private static final int    CHOOSE_STYLE_REQUEST          = 0;
    private static final int    DIALOG_XML_FILE_FATAL         = 0;
    private static final int    DIALOG_XML_FILE_ERRORS        = 1;

    private DebatingTimerService.DebatingTimerServiceBinder mBinder;
    private final BroadcastReceiver mGuiUpdateBroadcastReceiver = new GuiUpdateBroadcastReceiver();
    private final ServiceConnection mConnection = new DebatingTimerServiceConnection();

    //******************************************************************************************
    // Private classes
    //******************************************************************************************

    private class CentreControlButtonOnClickListener implements View.OnClickListener {
        @Override
        public void onClick(View pV) {
            if (mDebateManager == null) return;
            switch (mDebateManager.getStatus()) {
            case STOPPED_BY_USER:
                mDebateManager.resetSpeaker();
                break;
            default:
                break;
            }
            updateGui();
        }
    }

    /**
     * We can't just use an OnLongClickListener because the current time TextView also needs to
     * detect flings, just like the rest of the debate timer display.
     */
    private class CurrentTimeOnGestureListener extends DebateTimerDisplayOnGestureListener {

        @Override
        public void onLongPress(MotionEvent e) {
            editCurrentTimeStart();
            return;
        }

    }

    private class DebatingTimerFlashScreenListener implements FlashScreenListener {
        @Override
        public void flashScreen(boolean invert) {
            final int colour = (invert) ? 0xffffffff : 0x00000000;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    findViewById(R.id.debateActivityRootView).setBackgroundColor(colour);
                }
            });
        }
    }

    private class DebateTimerDisplayOnGestureListener extends SimpleOnGestureListener {

        // Constants for touch gesture sensitivity
        private static final float SWIPE_MIN_DISTANCE = 80;
        private static final float SWIPE_MAX_OFF_PATH = 250;
        private static final float SWIPE_THRESHOLD_VELOCITY = 200;
        @Override
        public boolean onDown(MotionEvent e) {
            // Ignore all touch events if no debate is loaded
            return (mDebateManager != null);
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {

            // The goToNextSpeech() and goToPreviousSpeech() methods check that the debate manager
            // is in a valid state, so we don't have to here.

            // If we go too far up or down, ignore as it's then not a horizontal swipe
            if (Math.abs(e1.getY() - e2.getY()) > SWIPE_MAX_OFF_PATH)
                return false;

            // If we go left or right far enough, then it's a horizontal swipe.
            // Check for the direction.
            if (Math.abs(e1.getX() - e2.getX()) > SWIPE_MIN_DISTANCE) {
                if (velocityX < -SWIPE_THRESHOLD_VELOCITY) {
                    goToNextSpeech();
                } else if (velocityX > SWIPE_THRESHOLD_VELOCITY) {
                    goToPreviousSpeech();
                } else {
                    return false;
                }
                return true;
            }
            return false;
        }

        @Override
        public boolean onSingleTapUp(MotionEvent e) {
            editCurrentTimeFinish(true);
            return true;
        }

    }

    /**
     * Defines call-backs for service binding, passed to bindService()
     */
    private class DebatingTimerServiceConnection implements ServiceConnection {

        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            mBinder = (DebatingTimerService.DebatingTimerServiceBinder) service;
            initialiseDebate();
            restoreBinder();
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            mDebateManager = null;
        }
    };

    private class FatalXmlError extends Exception {

        private static final long serialVersionUID = -1774973645180296278L;

        public FatalXmlError(String detailMessage, Throwable throwable) {
            super(detailMessage, throwable);
        }
    }

    private class GestureOnTouchListener implements View.OnTouchListener {

        GestureDetector gd;

        public GestureOnTouchListener(GestureDetector gestureDetector) {
            super();
            this.gd = gestureDetector;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (gd != null)
                return gd.onTouchEvent(event);
            return false;
        }

    }

    private final class GuiUpdateBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateGui();
        }
    }

    private class LeftControlButtonOnClickListener implements View.OnClickListener {
        @Override
        public void onClick(View pV) {
            if (mDebateManager == null) {
                Intent intent = new Intent(DebatingActivity.this, FormatChooserActivity.class);
                startActivityForResult(intent, CHOOSE_STYLE_REQUEST);
                return;
            }
            switch (mDebateManager.getStatus()) {
            case RUNNING:
                mDebateManager.stopTimer();
                break;
            case NOT_STARTED:
            case STOPPED_BY_BELL:
            case STOPPED_BY_USER:
                mDebateManager.startTimer();
                break;
            default:
                break;
            }
            updateGui();
        }
    }

    private class PlayBellButtonOnClickListener implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            mBinder.getAlertManager().playSingleBell();
        }
    }

    private class RightControlButtonOnClickListener implements View.OnClickListener {
        @Override
        public void onClick(View pV) {
            if (mDebateManager == null) return;
            switch (mDebateManager.getStatus()) {
            case NOT_STARTED:
            case STOPPED_BY_USER:
                goToNextSpeech();
                break;
            default:
                break;
            }
            updateGui();
        }
    }

    private enum OverallCountDirection {
        COUNT_UP, COUNT_DOWN
    }

    private enum UserPreferenceCountDirection {

        // These must match the values string array in the preference.xml file.
        // (We can pull strings from the resource automatically,
        // but we can't assign them to enums automatically.)
        ALWAYS_UP      ("alwaysUp"),
        GENERALLY_UP   ("generallyUp"),
        GENERALLY_DOWN ("generallyDown"),
        ALWAYS_DOWN    ("alwaysDown");

        private final String key;

        private UserPreferenceCountDirection(String key) {
            this.key = key;
        }

        public static UserPreferenceCountDirection toEnum(String key) {
            UserPreferenceCountDirection[] values = UserPreferenceCountDirection.values();
            for (int i = 0; i < values.length; i++)
                if (key.equals(values[i].key))
                    return values[i];
            throw new IllegalArgumentException(String.format("There is no enumerated constant '%s'", key));
        }

    }

    //******************************************************************************************
    // Public and protected methods
    //******************************************************************************************

    @Override
    public void onBackPressed() {

        // If no debate is loaded, exit.
        if (mDebateManager == null) {
            super.onBackPressed();
            return;
        }

        // If we're in editing mode, exit editing mode
        if (mIsEditingTime) {
            editCurrentTimeFinish(false);
            return;
        }

        // If the timer is stopped AND it's not the first speaker, go back one speaker.
        // Note: We do not just leave this check to goToPreviousSpeaker(), because we want to do
        // other things if it's not in a state in which it could go to the previous speaker.
        if (!mDebateManager.isFirstSpeech() && !mDebateManager.isRunning()) {
            goToPreviousSpeech();
            return;

        // Otherwise, behave normally (i.e. exit).
        // Note that if the timer is running, the service will remain present in the
        // background, so this doesn't stop a running timer.
        } else {
            super.onBackPressed();
            return;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.debating_activity_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        editCurrentTimeFinish(false);
        switch (item.getItemId()) {
        case R.id.prevSpeaker:
            goToPreviousSpeech();
            return true;
        case R.id.chooseFormat:
            Intent getStyleIntent = new Intent(this, FormatChooserActivity.class);
            getStyleIntent.putExtra(FormatChooserActivity.EXTRA_XML_FILE_NAME, mFormatXmlFileName);
            startActivityForResult(getStyleIntent, CHOOSE_STYLE_REQUEST);
            return true;
        case R.id.resetDebate:
            if (mDebateManager == null) return true;
            resetDebate();
            updateGui();
            return true;
        case R.id.settings:
            startActivity(new Intent(this, GlobalSettingsActivity.class));
            return true;
        default:
            return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {

        MenuItem prevSpeakerItem = menu.findItem(R.id.prevSpeaker);
        MenuItem resetDebateItem = menu.findItem(R.id.resetDebate);

        if (mDebateManager != null) {
            prevSpeakerItem.setEnabled(!mDebateManager.isFirstSpeech() && !mDebateManager.isRunning() && !mIsEditingTime);
            resetDebateItem.setEnabled(true);
        } else {
            prevSpeakerItem.setEnabled(false);
            resetDebateItem.setEnabled(false);
        }

        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == CHOOSE_STYLE_REQUEST && resultCode == RESULT_OK) {
            String filename = data.getStringExtra(FormatChooserActivity.EXTRA_XML_FILE_NAME);
            if (filename != null) {
                Log.v(this.getClass().getSimpleName(), String.format("Got file name %s", filename));
                setXmlFileName(filename);
                resetDebateWithoutToast();
            }
            // Do nothing if cancelled or error.
        }

    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.debate_activity);

        mFilesManager = new FormatXmlFilesManager(this);

        mDebateTimerViewFlipper    = (ViewFlipper)    findViewById(R.id.debateTimerDisplayFlipper);
        mDebateTimerDisplays       = new RelativeLayout[2];
        mDebateTimerDisplays[0]    = (RelativeLayout) findViewById(R.id.debateTimerDisplay0);
        mDebateTimerDisplays[1]    = (RelativeLayout) findViewById(R.id.debateTimerDisplay1);

        for (int i = 0; i < mDebateTimerDisplays.length; i++) {
            TimePicker currentTimePicker = (TimePicker) mDebateTimerDisplays[i].findViewById(R.id.currentTimePicker);
            currentTimePicker.setIs24HourView(true);
        }

        mDebateTimerViewFlipper.setDisplayedChild(mCurrentDebateTimerDisplayIndex);

        mLeftControlButton   = (Button) findViewById(R.id.leftControlButton);
        mCentreControlButton = (Button) findViewById(R.id.centreControlButton);
        mRightControlButton  = (Button) findViewById(R.id.rightControlButton);
        mPlayBellButton      = (Button) findViewById(R.id.playBellButton);

        //
        // OnClickListeners
        mLeftControlButton  .setOnClickListener(new LeftControlButtonOnClickListener());
        mCentreControlButton.setOnClickListener(new CentreControlButtonOnClickListener());
        mRightControlButton .setOnClickListener(new RightControlButtonOnClickListener());
        mPlayBellButton     .setOnClickListener(new PlayBellButtonOnClickListener());

        mLastStateBundle = savedInstanceState; // This could be null

        //
        // OnTouchListeners
        GestureDetector gd1 = new GestureDetector(new DebateTimerDisplayOnGestureListener());
        View displayFlipper = findViewById(R.id.debateTimerDisplayFlipper);
        displayFlipper.setOnTouchListener(new GestureOnTouchListener(gd1));

        GestureDetector gd2 = new GestureDetector(new CurrentTimeOnGestureListener());
        for (int i = 0; i < mDebateTimerDisplays.length; i++) {
            View currentTimeText = mDebateTimerDisplays[i].findViewById(R.id.currentTime);
            currentTimeText.setOnTouchListener(new GestureOnTouchListener(gd2));
        }

        //
        // Find the style file name
        String filename = loadXmlFileName();

        // If there doesn't appear to be an existing style selected, then start
        // the Activity to select the style immediately, and don't bother with the
        // rest.
        if (filename == null) {
            Intent getStyleIntent = new Intent(DebatingActivity.this, FormatChooserActivity.class);
            startActivityForResult(getStyleIntent, CHOOSE_STYLE_REQUEST);
        }

        //
        // Start the timer service
        Intent intent = new Intent(this, DebatingTimerService.class);
        startService(intent);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected Dialog onCreateDialog(int id, Bundle bundle) {
        switch (id) {
        case DIALOG_XML_FILE_FATAL:
            return getFatalProblemWithXmlFileDialog(bundle);
        case DIALOG_XML_FILE_ERRORS:
            return getErrorsWithXmlFileDialog(bundle);
        }
        return super.onCreateDialog(id);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        unbindService(mConnection);

        boolean keepRunning = false;
        if (mDebateManager != null) {
            if (mDebateManager.isRunning()) {
                keepRunning = true;
            }
        }
        if (!keepRunning) {
            Intent intent = new Intent(this, DebatingTimerService.class);
            stopService(intent);
            Log.i(this.getClass().getSimpleName(), "Timer is not running, stopped service");
        } else {
            Log.i(this.getClass().getSimpleName(), "Timer is running, keeping service alive");
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        restoreBinder();
        LocalBroadcastManager.getInstance(this).registerReceiver(mGuiUpdateBroadcastReceiver,
                new IntentFilter(DebatingTimerService.UPDATE_GUI_BROADCAST_ACTION));

        applyPreferences();
        updateGui();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mBinder != null) {
            AlertManager am = mBinder.getAlertManager();
            if (am != null) {
                am.activityStop();
            }
        }
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mGuiUpdateBroadcastReceiver);
    }

    @Override
    protected void onSaveInstanceState(Bundle bundle) {
        if (mDebateManager != null)
            mDebateManager.saveState(BUNDLE_SUFFIX_DEBATE_MANAGER, bundle);
    }


    //******************************************************************************************
    // Private methods
    //******************************************************************************************

    /**
     * Gets the preferences from the shared preferences file and applies them.
     */
    private void applyPreferences() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean silentMode, vibrateMode, overtimeBellsEnabled, keepScreenOn;
        int firstOvertimeBell, overtimeBellPeriod;
        String userCountDirectionValue;
        FlashScreenMode flashScreenMode;

        Resources res = getResources();

        try {

            // The boolean preferences
            silentMode = prefs.getBoolean(res.getString(R.string.PrefSilentModeKey),
                    res.getBoolean(R.bool.DefaultPrefSilentMode));
            vibrateMode = prefs.getBoolean(res.getString(R.string.PrefVibrateModeKey),
                    res.getBoolean(R.bool.DefaultPrefVibrateMode));
            overtimeBellsEnabled = prefs.getBoolean(res.getString(R.string.PrefOvertimeBellsEnableKey),
                    res.getBoolean(R.bool.DefaultPrefOvertimeBellsEnable));
            keepScreenOn = prefs.getBoolean(res.getString(R.string.PrefKeepScreenOnKey),
                    res.getBoolean(R.bool.DefaultPrefKeepScreenOn));

            // Overtime bell integers
            if (overtimeBellsEnabled) {
                firstOvertimeBell  = prefs.getInt(res.getString(R.string.PrefFirstOvertimeBellKey),
                        res.getInteger(R.integer.DefaultPrefFirstOvertimeBell));
                overtimeBellPeriod = prefs.getInt(res.getString(R.string.PrefOvertimeBellPeriodKey),
                        res.getInteger(R.integer.DefaultPrefOvertimeBellPeriod));
            } else {
                firstOvertimeBell = 0;
                overtimeBellPeriod = 0;
            }

            // List preference: Count direction
            userCountDirectionValue = prefs.getString(res.getString(R.string.PrefCountDirectionKey),
                    res.getString(R.string.DefaultPrefCountDirection));
            mUserCountDirection = UserPreferenceCountDirection.toEnum(userCountDirectionValue);

            // List preference: Flash screen mode
            // This changed from a boolean to a list preference in version 0.6, so there is
            // backwards compatibility to take care of.  Backwards compatibility applies if
            // (a) the list preference is NOT present AND (b) the boolean preference IS present.
            // In this case, retrieve the boolean preference, delete it and write the corresponding
            // list preference.  In all other cases, just take the list preference (using the
            // normal default mechanism if it isn't present, i.e. neither are present).

            if (!prefs.contains(res.getString(R.string.PrefFlashScreenModeKey)) &&
                    prefs.contains(res.getString(R.string.PrefFlashScreenBoolKey))) {
                // Boolean preference.
                // First, get the string and convert it to an enum.
                boolean flashScreenModeBool = prefs.getBoolean(
                        res.getString(R.string.PrefFlashScreenBoolKey), false);
                flashScreenMode = (flashScreenModeBool) ? FlashScreenMode.SOLID_FLASH : FlashScreenMode.OFF;

                // Then, convert that enum to the list preference value (a string) and write that
                // back to the preferences.  Also, remove the old boolean preference.
                String flashStringModePrefValue = flashScreenMode.toPrefValue();
                SharedPreferences.Editor editor = prefs.edit();
                editor.putString(res.getString(R.string.PrefFlashScreenModeKey), flashStringModePrefValue);
                editor.remove(res.getString(R.string.PrefFlashScreenBoolKey));
                editor.commit();
                Log.w(this.getClass().getSimpleName(),
                        String.format("flashScreenMode: replaced boolean preference with list preference: %s", flashStringModePrefValue));

            } else {
                // List preference.
                // Get the string and convert it to an enum.
                String flashScreenModeValue;
                flashScreenModeValue = prefs.getString(res.getString(R.string.PrefFlashScreenModeKey),
                        res.getString(R.string.DefaultPrefFlashScreenMode));
                flashScreenMode = FlashScreenMode.toEnum(flashScreenModeValue);
            }


        } catch (ClassCastException e) {
            Log.e(this.getClass().getSimpleName(), "applyPreferences: caught ClassCastException!");
            return;
        }

        if (mDebateManager != null) {
            mDebateManager.setOvertimeBells(firstOvertimeBell, overtimeBellPeriod);
        } else {
            Log.w(this.getClass().getSimpleName(), "applyPreferences: Couldn't restore overtime bells, mDebateManager doesn't yet exist");
        }

        if (mBinder != null) {
            AlertManager am = mBinder.getAlertManager();

            // Volume control stream is linked to silent mode
            am.setSilentMode(silentMode);
            setVolumeControlStream((silentMode) ? AudioManager.STREAM_RING : AudioManager.STREAM_MUSIC);

            am.setVibrateMode(vibrateMode);
            am.setKeepScreenOn(keepScreenOn);
            am.setFlashScreenListener((flashScreenMode != FlashScreenMode.OFF) ? new DebatingTimerFlashScreenListener() : null);
            am.setFlashScreenMode(flashScreenMode);
            Log.v(this.getClass().getSimpleName(), "applyPreferences: successfully applied");
        } else {
            Log.w(this.getClass().getSimpleName(), "applyPreferences: Couldn't restore AlertManager preferences; mBinder doesn't yet exist");
        }
    }

    /**
     * Builds a <code>DebateFormat</code> from a specified XML file. Shows a <code>Dialog</code> if
     * the debate format builder logged non-fatal errors.
     * @param filename the file name of the XML file
     * @return the built <code>DebateFormat</code>
     * @throws FatalXmlError if there was any problem, which could include:
     * <ul><li>A problem opening or reading the file</li>
     * <li>A problem parsing the XML file</li>
     * <li>That there were no speeches in this debate format</li>
     * </ul>
     * The message of the exception will be human-readable and can be displayed in a dialogue box.
     */
    private DebateFormat buildDebateFromXml(String filename) throws FatalXmlError {
        DebateFormatBuilderFromXml dfbfx = new DebateFormatBuilderFromXml(this);
        InputStream is = null;
        DebateFormat df;

        try {
            is = mFilesManager.open(filename);
        } catch (IOException e) {
            throw new FatalXmlError(getString(R.string.FatalProblemWithXmlFileMessage_CannotFind, filename), e);
        }

        try {
            df = dfbfx.buildDebateFromXml(is);
        } catch (IOException e) {
            throw new FatalXmlError(getString(R.string.FatalProblemWithXmlFileMessage_CannotRead, filename), e);
        } catch (SAXException e) {
            throw new FatalXmlError(getString(
                    R.string.FatalProblemWithXmlFileMessage_BadXml, filename, e.getMessage()), e);
        } catch (IllegalStateException e) {
            throw new FatalXmlError(getString(
                    R.string.FatalProblemWithXmlFileMessage_NoSpeeches, filename), e);
        }

        if (dfbfx.hasErrors()) {
            Bundle bundle = new Bundle();
            bundle.putStringArrayList(DIALOG_BUNDLE_XML_ERROR_LOG, dfbfx.getErrorLog());
            removeDialog(DIALOG_XML_FILE_ERRORS);
            showDialog(DIALOG_XML_FILE_ERRORS, bundle);
        }

        return df;
    }

    /**
     * Displays the time picker to edit the current time.
     * Does nothing if there is no debate loaded or if the timer is running.
     */
    private void editCurrentTimeStart() {

        // Check that things are in a valid state to enter edit time mode
        // If they aren't, return straight away
        if (mDebateManager == null) return;
        if (mDebateManager.isRunning()) return;

        // Only if things were in a valid state do we enter edit time mode
        mIsEditingTime = true;

        TimePicker currentTimePicker = (TimePicker) getCurrentDebateTimerDisplay().findViewById(R.id.currentTimePicker);
        long currentTime = mDebateManager.getCurrentSpeechTime();

        // Invert the time if in count-down mode
        currentTime = subtractFromSpeechLengthIfCountingDown(currentTime);

        // Limit to the allowable time range
        if (currentTime < 0) currentTime = 0;
        if (currentTime >= 24 * 60) currentTime = 24 * 60 - 1;

        // We're using this in hours and minutes, not minutes and seconds
        currentTimePicker.setCurrentHour((int) (currentTime / 60));
        currentTimePicker.setCurrentMinute((int) (currentTime % 60));

        updateGui();
    }

    /**
     * Finishes editing the current time and restores the GUI to its prior state.
     * @param save true if the edited time should become the new current time, false if it should
     * be discarded.
     */
    private void editCurrentTimeFinish(boolean save) {

        TimePicker currentTimePicker = (TimePicker) getCurrentDebateTimerDisplay().findViewById(R.id.currentTimePicker);

        currentTimePicker.clearFocus();

        // Hide the keyboard
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(currentTimePicker.getWindowToken(), 0);

        if (save && mDebateManager != null && mIsEditingTime) {
            // We're using this in hours and minutes, not minutes and seconds
            int minutes = currentTimePicker.getCurrentHour();
            int seconds = currentTimePicker.getCurrentMinute();
            long newTime = minutes * 60 + seconds;
            // Invert the time if in count-down mode
            newTime = subtractFromSpeechLengthIfCountingDown(newTime);
            mDebateManager.setCurrentSpeechTime(newTime);
        }

        mIsEditingTime = false;

        updateGui();

    }

    /**
     * Assembles the speech format and user count directions to find the count direction to use
     * currently.
     * @return OverallCountDirection.UP or OverallCountDirection.DOWN
     */
    private OverallCountDirection getCountDirection() {

        // If the user has specified always up or always down, that takes priority.
        if (mUserCountDirection == UserPreferenceCountDirection.ALWAYS_DOWN)
            return OverallCountDirection.COUNT_DOWN;
        if (mUserCountDirection == UserPreferenceCountDirection.ALWAYS_UP)
            return OverallCountDirection.COUNT_UP;

        // If the user hasn't specified, and the speech format has specified a count direction,
        // use the speech format suggestion.
        if (mDebateManager != null) {
            SpeechFormat currentSpeechFormat = mDebateManager.getCurrentSpeechFormat();
            CountDirection sfCountDirection = currentSpeechFormat.getCountDirection();
            if (sfCountDirection == CountDirection.COUNT_DOWN)
                return OverallCountDirection.COUNT_DOWN;
            if (sfCountDirection == CountDirection.COUNT_UP)
                return OverallCountDirection.COUNT_UP;
        }

        // Otherwise, use the user setting.
        if (mUserCountDirection == UserPreferenceCountDirection.GENERALLY_DOWN)
            return OverallCountDirection.COUNT_DOWN;
        if (mUserCountDirection == UserPreferenceCountDirection.GENERALLY_UP)
            return OverallCountDirection.COUNT_UP;

        // We've now covered all possibilities.  But just in case (and to satisfy the compiler)...
        return OverallCountDirection.COUNT_UP;

    }

    /**
     * @return the current RelativeLayout for the debate timer display
     */
    private RelativeLayout getCurrentDebateTimerDisplay() {
        return mDebateTimerDisplays[mCurrentDebateTimerDisplayIndex];
    }

    private Dialog getErrorsWithXmlFileDialog(Bundle bundle) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        String errorMessage = getString(R.string.ErrorsInXmlFileDialogMessagePrefix);

        ArrayList<String> errorLog = bundle.getStringArrayList(DIALOG_BUNDLE_XML_ERROR_LOG);
        Iterator<String> errorIterator = errorLog.iterator();

        while (errorIterator.hasNext()) {
            errorMessage = errorMessage.concat("\n");
            errorMessage = errorMessage.concat(errorIterator.next());
        }

        builder.setTitle(R.string.ErrorsInXmlFileDialogTitle)
               .setMessage(errorMessage)
               .setCancelable(true)
               .setPositiveButton(R.string.ErrorsInXmlFileDialogButton, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                });

        return builder.create();
    }

    private Dialog getFatalProblemWithXmlFileDialog(Bundle bundle) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        String errorMessage = bundle.getString(DIALOG_BUNDLE_FATAL_MESSAGE);
        errorMessage = errorMessage.concat(getString(R.string.FatalProblemWithXmlFileMessageSuffix));

        builder.setTitle(R.string.FatalProblemWithXmlFileDialogTitle)
               .setMessage(errorMessage)
               .setCancelable(true)
               .setPositiveButton(R.string.FatalProblemWithXmlFileDialogButton, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Intent intent = new Intent(DebatingActivity.this, FormatChooserActivity.class);
                        startActivityForResult(intent, CHOOSE_STYLE_REQUEST);
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        DebatingActivity.this.finish();
                    }
                });

        return builder.create();
    }

    /**
     * Goes to the next speech.
     * Does nothing if there is no debate loaded, if the current speech is the last speech, if
     * the timer is running, or if the current time is being edited.
     */
    private void goToNextSpeech() {

        if (mDebateManager == null) return;
        if (mDebateManager.isRunning()) return;
        if (mDebateManager.isLastSpeech()) return;
        if (mIsEditingTime) return;

        // Swap the current display index
        mCurrentDebateTimerDisplayIndex = (mCurrentDebateTimerDisplayIndex == 1) ? 0 : 1;

        mDebateManager.goToNextSpeaker();
        updateDebateTimerDisplay(mCurrentDebateTimerDisplayIndex);
        mDebateTimerViewFlipper.setInAnimation(AnimationUtils.loadAnimation(
                DebatingActivity.this, R.anim.slide_from_right));
        mDebateTimerViewFlipper.setOutAnimation(AnimationUtils.loadAnimation(
                DebatingActivity.this, R.anim.slide_to_left));
        mDebateTimerViewFlipper.setDisplayedChild(mCurrentDebateTimerDisplayIndex);

        updateGui();

    }

    /**
     * Goes to the previous speech.
     * Does nothing if there is no debate loaded, if the current speech is the first speech, if
     * the timer is running, or if the current time is being edited.
     */
    private void goToPreviousSpeech() {

        if (mDebateManager == null) return;
        if (mDebateManager.isRunning()) return;
        if (mDebateManager.isFirstSpeech()) return;
        if (mIsEditingTime) return;

        // Swap the current display index
        mCurrentDebateTimerDisplayIndex = (mCurrentDebateTimerDisplayIndex == 1) ? 0 : 1;

        mDebateManager.goToPreviousSpeaker();
        updateDebateTimerDisplay(mCurrentDebateTimerDisplayIndex);
        mDebateTimerViewFlipper.setInAnimation(AnimationUtils.loadAnimation(
                DebatingActivity.this, R.anim.slide_from_left));
        mDebateTimerViewFlipper.setOutAnimation(AnimationUtils.loadAnimation(
                DebatingActivity.this, R.anim.slide_to_right));
        mDebateTimerViewFlipper.setDisplayedChild(mCurrentDebateTimerDisplayIndex);

        updateGui();

    }

    private void initialiseDebate() {
        if (mFormatXmlFileName == null) {
            Log.w(this.getClass().getSimpleName(), "Tried to initialise debate with null file");
            return;
        }

        mDebateManager = mBinder.getDebateManager();
        if (mDebateManager == null) {

            DebateFormat df;
            try {
                df = buildDebateFromXml(mFormatXmlFileName);
            } catch (FatalXmlError e) {
                removeDialog(DIALOG_XML_FILE_FATAL);
                Bundle bundle = new Bundle();
                bundle.putString(DIALOG_BUNDLE_FATAL_MESSAGE, e.getMessage());
                showDialog(DIALOG_XML_FILE_FATAL, bundle);
                return;
            }

            mDebateManager = mBinder.createDebateManager(df);

            // We only restore the state if there wasn't an existing debate, i.e.
            // if the service wasn't already running.  Also, only do this once (so set it
            // to null once restored).
            if (mLastStateBundle != null) {
                mDebateManager.restoreState(BUNDLE_SUFFIX_DEBATE_MANAGER, mLastStateBundle);
                mLastStateBundle = null;
            }
        }

        applyPreferences();
        updateGui();
    }

    private String loadXmlFileName() {
        SharedPreferences sp = getPreferences(MODE_PRIVATE);
        String filename = sp.getString(PREFERENCE_XML_FILE_NAME, null);
        mFormatXmlFileName = filename;
        return filename;
    }

    private void resetDebate() {
        resetDebateWithoutToast();
        Toast.makeText(this, R.string.ResetDebateToastText, Toast.LENGTH_SHORT).show();
    }

    private void resetDebateWithoutToast() {
        if (mBinder == null) return;
        mBinder.releaseDebateManager();
        initialiseDebate();
    }

    private void restoreBinder() {
        if (mBinder != null) {
            AlertManager am = mBinder.getAlertManager();
            if (am != null) {
                am.activityStart();
            }
        }
    }

    // Sets the text and visibility of a single button
    private void setButton(Button button, int resid) {
        button.setText(resid);
        int visibility = (resid == R.string.NullButtonText) ? View.GONE : View.VISIBLE;
        button.setVisibility(visibility);
    }

    // Sets the text, visibility and "weight" of all buttons
    private void setButtons(int leftResid, int centreResid, int rightResid) {
        setButton(mLeftControlButton, leftResid);
        setButton(mCentreControlButton, centreResid);
        setButton(mRightControlButton, rightResid);

        // If there are exactly two buttons, make the weight of the left button double,
        // so that it fills two-thirds of the width of the screen.
        float leftControlButtonWeight = (float) ((centreResid == R.string.NullButtonText && rightResid != R.string.NullButtonText) ? 2.0 : 1.0);
        mLeftControlButton.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, leftControlButtonWeight));
    }

    private void setXmlFileName(String filename) {
        mFormatXmlFileName = filename;
        SharedPreferences sp = getPreferences(MODE_PRIVATE);
        Editor editor = sp.edit();
        editor.putString(PREFERENCE_XML_FILE_NAME, filename);
        editor.commit();
    }

    /**
     *  Updates the buttons according to the current status of the debate
     *  The buttons are allocated as follows:
     *  When at startOfSpeaker: [Start] [Next Speaker]
     *  When running:           [Stop]
     *  When stopped by user:   [Resume] [Restart] [Next Speaker]
     *  When stopped by alarm:  [Resume]
     *  The [Bell] button always is on the right of any of the above three buttons.
     */
    private void updateControls() {
        View currentTimeText   = getCurrentDebateTimerDisplay().findViewById(R.id.currentTime);
        View currentTimePicker = getCurrentDebateTimerDisplay().findViewById(R.id.currentTimePicker);

        if (mDebateManager != null) {

            // If it's the last speaker, don't show a "next speaker" button.
            // Show a "restart debate" button instead.
            switch (mDebateManager.getStatus()) {
            case NOT_STARTED:
                setButtons(R.string.StartTimerButtonText, R.string.NullButtonText, R.string.NextSpeakerButtonText);
                break;
            case RUNNING:
                setButtons(R.string.StopTimerButtonText, R.string.NullButtonText, R.string.NullButtonText);
                break;
            case STOPPED_BY_BELL:
                setButtons(R.string.ResumeTimerAfterAlarmButtonText, R.string.NullButtonText, R.string.NullButtonText);
                break;
            case STOPPED_BY_USER:
                setButtons(R.string.ResumeTimerAfterUserStopButtonText, R.string.ResetTimerButtonText, R.string.NextSpeakerButtonText);
                break;
            default:
                break;
            }

            if (mIsEditingTime) {
                // Show the time picker, not the text
                currentTimeText.setVisibility(View.GONE);
                currentTimePicker.setVisibility(View.VISIBLE);

                // Disable all control buttons
                mLeftControlButton.setEnabled(false);
                mCentreControlButton.setEnabled(false);
                mRightControlButton.setEnabled(false);
            } else {
                // Show the time as text, not the picker
                currentTimeText.setVisibility(View.VISIBLE);
                currentTimePicker.setVisibility(View.GONE);

                // Disable the [Next Speaker] button if there are no more speakers
                mLeftControlButton.setEnabled(true);
                mCentreControlButton.setEnabled(true);
                mRightControlButton.setEnabled(!mDebateManager.isLastSpeech());
            }

        } else {
            // If no debate is loaded, show only one control button, which leads the user to
            // choose a style.
            // (Keep the play bell button enabled.)
            setButtons(R.string.NoDebateLoadedButtonText, R.string.NullButtonText, R.string.NullButtonText);
            mLeftControlButton.setEnabled(true);
            mCentreControlButton.setEnabled(false);
            mRightControlButton.setEnabled(false);
        }

        // Show or hide the [Bell] button
        updatePlayBellButton();
    }

    /**
     * Updates the debate timer display (including speech name, period name, etc.) in a given view.
     * The view should be the <code>RelativeLayout</code> in debate_timer_display.xml.
     * @param debateTimerDisplayIndex The index of the debate timer display that will be updated.
     */
    private void updateDebateTimerDisplay(int debateTimerDisplayIndex) {

        View v = mDebateTimerDisplays[debateTimerDisplayIndex];

        TextView periodDescriptionText = (TextView) v.findViewById(R.id.periodDescriptionText);
        TextView speechNameText        = (TextView) v.findViewById(R.id.speechNameText);
        TextView currentTimeText       = (TextView) v.findViewById(R.id.currentTime);
        TextView nextTimeText          = (TextView) v.findViewById(R.id.nextTime);
        TextView finalTimeText         = (TextView) v.findViewById(R.id.finalTime);

        if (mDebateManager != null) {

            SpeechFormat currentSpeechFormat = mDebateManager.getCurrentSpeechFormat();
            PeriodInfo   currentPeriodInfo   = mDebateManager.getCurrentPeriodInfo();

            speechNameText.setText(mDebateManager.getCurrentSpeechName());
            speechNameText.setBackgroundColor(currentPeriodInfo.getBackgroundColor());
            periodDescriptionText.setText(currentPeriodInfo.getDescription());
            periodDescriptionText.setBackgroundColor(currentPeriodInfo.getBackgroundColor());

            long currentSpeechTime = mDebateManager.getCurrentSpeechTime();
            Long nextBellTime = mDebateManager.getNextBellTime();
            boolean nextBellIsPause = mDebateManager.isNextBellPause();

            // Take count direction into account for display
            currentSpeechTime = subtractFromSpeechLengthIfCountingDown(currentSpeechTime);
            if (nextBellTime != null)
                nextBellTime = subtractFromSpeechLengthIfCountingDown(nextBellTime);

            Resources resources = getResources();
            int currentTimeTextColor;
            if (mDebateManager.isOvertime())
                currentTimeTextColor = resources.getColor(R.color.overtime);
            else
                currentTimeTextColor = resources.getColor(android.R.color.primary_text_dark);
            currentTimeText.setText(secsToText(currentSpeechTime));
            currentTimeText.setTextColor(currentTimeTextColor);

            if (nextBellTime != null) {
                if (nextBellIsPause) {
                    nextTimeText.setText(String.format(
                            this.getString(R.string.NextBellWithPauseText),
                            secsToText(nextBellTime)));
                } else {
                    nextTimeText.setText(String.format(this.getString(R.string.NextBellText),
                            secsToText(nextBellTime)));
                }
            } else {
                nextTimeText.setText(this.getString(R.string.NoMoreBellsText));
            }
            finalTimeText.setText(String.format(
                this.getString(R.string.SpeechLengthText),
                secsToText(currentSpeechFormat.getSpeechLength())
            ));

        } else {
            // Blank out all the fields
            periodDescriptionText.setText(R.string.NoDebateLoadedText);
            speechNameText.setText("");
            periodDescriptionText.setBackgroundColor(0);
            speechNameText.setBackgroundColor(0);
            currentTimeText.setText("");
            nextTimeText.setText("");
            finalTimeText.setText("");
        }

    }

    /**
     * Updates the GUI (in the general case).
     */
    private void updateGui() {
        updateDebateTimerDisplay(mCurrentDebateTimerDisplayIndex);
        updateControls();

        if (mDebateManager != null) {
            this.setTitle(getString(R.string.DebatingActivityTitleBarWithFormatName, mDebateManager.getDebateFormatName()));
        } else {
            setTitle(R.string.DebatingActivityTitleBarWithoutFormatName);
        }

    }

    private void updatePlayBellButton() {
        if (mBinder != null)
            mPlayBellButton.setVisibility((mBinder.getAlertManager().isSilentMode()) ? View.GONE : View.VISIBLE);
    }

    private static String secsToText(long time) {
        if (time >= 0) {
            return String.format("%02d:%02d", time / 60, time % 60);
        } else {
            return String.format("%02d:%02d over", -time / 60, -time % 60);
        }
    }

    /**
     * Returns the number of seconds that would be displayed, taking into account the count
     * direction.  If the overall count direction is <code>COUNT_DOWN</code> and there is a speech
     * format ready, it returns (speechLength - time).  Otherwise, it just returns time.
     * @param time the time that is wished to be formatted (in seconds)
     * @return the time that would be displayed (as an integer, number of seconds)
     */
    private long subtractFromSpeechLengthIfCountingDown(long time) {
        if (mDebateManager != null)
            if (getCountDirection() == OverallCountDirection.COUNT_DOWN)
                return mDebateManager.getCurrentSpeechFormat().getSpeechLength() - time;
        return time;
    }

}

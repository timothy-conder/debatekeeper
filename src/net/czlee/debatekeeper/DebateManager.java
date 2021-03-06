/*
 * Copyright (C) 2012 Chuan-Zheng Lee
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

import java.util.ArrayList;

import net.czlee.debatekeeper.DebatingTimerService.GuiUpdateBroadcastSender;
import android.os.Bundle;


/**
 * DebateManager manages a debate by keeping track of speeches and running the speech timers.
 *
 * It is given a {@link DebateFormat}, which cannot then be changed.  If it must be changed, this
 * <code>DebateManager</code> must be destroyed and another one created with the new
 * <code>DebateFormat</code>.
 *
 * <p>DebateManager is also capable of:</p>
 *  <ul>
 *  <li> navigating forwards and backwards between speakers
 *  <li> storing times for speeches
 *  </ul>
 *
 * DebateManager is NOT capable of:
 *  <ul>
 *  <li> handling the GUI, but it sends a message to the DebatingActivity to update the GUI
 *  </ul>
 *
 * The internal mechanics of a single speech are handled by {@link SpeechManager}.
 *
 * It does not handle the GUI.
 *
 * @author Chuan-Zheng Lee
 * @since  2012-06-09
 */

public class DebateManager {

    private final DebateFormat  mDebateFormat;
    private final SpeechManager mSpeechManager;

    private final ArrayList<Long> mSpeechTimes;

    private int mCurrentSpeechIndex;

    private static final String BUNDLE_SUFFIX_INDEX        = ".csi";
    private static final String BUNDLE_SUFFIX_SPEECH       = ".sm";
    private static final String BUNDLE_SUFFIX_SPEECH_TIMES = ".st";

    //******************************************************************************************
    // Public methods
    //******************************************************************************************

    /**
     * Constructor.
     * @param df
     */
    public DebateManager(DebateFormat df, AlertManager am) {
        super();
        this.mDebateFormat  = df;
        this.mSpeechManager = new SpeechManager(am);
        this.mSpeechTimes   = new ArrayList<Long>();

        this.mSpeechTimes.ensureCapacity(df.numberOfSpeeches());
        for (int i = 0; i < df.numberOfSpeeches(); i++)
            mSpeechTimes.add((long) 0);

        this.mCurrentSpeechIndex = 0;
        this.mSpeechManager.loadSpeech(mDebateFormat.getSpeechFormat(mCurrentSpeechIndex));

    }

    /**
     * Sets a broadcast sender for this speech manager.
     * <code>DebateManager</code> will call <code>sendBroadcast()</code> on the broadcast sender
     * when the timer counts up/down.
     * @param sender the {@link GuiUpdateBroadcastSender}
     */
    public void setBroadcastSender(GuiUpdateBroadcastSender sender) {
        this.mSpeechManager.setBroadcastSender(sender);
    }

    /**
     * Starts the timer.
     */
    public void startTimer() {
        mSpeechManager.start();
    }

    /**
     * Stops the timer.
     */
    public void stopTimer() {
        mSpeechManager.stop();
    }

    /**
     * Resets the current speaker.
     */
    public void resetSpeaker() {
        mSpeechManager.reset();
    }

    /**
     * Moves to the next speaker.
     * If already on the last speaker, reloads the last speaker.
     */
    public void goToNextSpeaker() {
        saveSpeech();
        mSpeechManager.stop();
        if (!isLastSpeech()) mCurrentSpeechIndex++;
        loadSpeech();
    }

    /**
     * Moves to the previous speaker.
     * If already on the first speaker, reloads the first speaker.
     */
    public void goToPreviousSpeaker() {
        saveSpeech();
        mSpeechManager.stop();
        if (!isFirstSpeech()) mCurrentSpeechIndex--;
        loadSpeech();
    }

    /**
     * @return the current state
     */
    public SpeechManager.DebatingTimerState getStatus() {
        return mSpeechManager.getStatus();
    }

    /**
     * @return <code>true</code> if the timer is running, <code>false</code> otherwise
     */
    public boolean isRunning() {
        return mSpeechManager.getStatus() == SpeechManager.DebatingTimerState.RUNNING;
    }

    /**
     * @return <code>true</code> if the current speech is the first speech, <code>false</code>
     * otherwise
     */
    public boolean isFirstSpeech() {
        return mCurrentSpeechIndex == 0;
    }

    /**
     * @return <code>true</code> if the current speech is the last speech, <code>false</code>
     * otherwise
     */
    public boolean isLastSpeech() {
        return mCurrentSpeechIndex == mDebateFormat.numberOfSpeeches() - 1;
    }

    /**
     * @return <code>true</code> if the next bell will pause the timer, <code>false</code> otherwise.
     * Returns <code>false</code> if there are no more bells or if there are only overtime bells left.
     */
    public boolean isNextBellPause() {
        return mSpeechManager.isNextBellPause();
    }

    /**
     * Checks if the current speech is in overtime
     * @return <code>true</code> is the current speech is in overtime, <code>false</code> otherwise.
     */
    public boolean isOvertime() {
        return mSpeechManager.isOvertime();
    }

    /**
     * @return the current time for the current speaker
     */
    public long getCurrentSpeechTime() {
        return mSpeechManager.getCurrentTime();
    }

    /**
     * @return the next bell time, or <code>null</code> if there are no more bells
     */
    public Long getNextBellTime() {
        return mSpeechManager.getNextBellTime();
    }

    /**
     * @return the current period info to be displayed
     */
    public PeriodInfo getCurrentPeriodInfo() {
        return mSpeechManager.getCurrentPeriodInfo();
    }

    /**
     * @return an ArrayList of speech times
     */
    public ArrayList<Long> getSpeechTimes() {
        return mSpeechTimes;
    }

    /**
     * @return the debate format name
     */
    public String getDebateFormatName() {
        return mDebateFormat.getName();
    }

    /**
     * @return the current speech name
     */
    public String getCurrentSpeechName() {
        return mDebateFormat.getSpeechName(mCurrentSpeechIndex);
    }

    /**
     * @return the current {@link SpeechFormat}
     */
    public SpeechFormat getCurrentSpeechFormat() {
        return mSpeechManager.getSpeechFormat();
    }

    /**
     * Sets the current speech time.
     * This method sets the current speech time even if the timer is running.
     * @param seconds the new time in seconds
     */
    public void setCurrentSpeechTime(long seconds) {
        mSpeechManager.setCurrentTime(seconds);
    }

    /**
     * Sets the overtime bell specifications
     * @param firstBell The number of seconds after the finish time to ring the first overtime bell
     * @param period The time in between subsequence overtime bells
     */
    public void setOvertimeBells(long firstBell, long period) {
        mSpeechManager.setOvertimeBells(firstBell, period);
    }

    /**
     * Saves the state of this <code>DebateManager</code> to a {@link Bundle}.
     * @param key A String to uniquely distinguish this <code>DebateManager</code> from any other
     *        objects that might be stored in the same Bundle.
     * @param bundle The Bundle to which to save this information.
     */
    public void saveState(String key, Bundle bundle) {

        // Take note of which speech we're on
        bundle.putInt(key + BUNDLE_SUFFIX_INDEX, mCurrentSpeechIndex);

        // Save the speech times
        long[] speechTimes = new long[mSpeechTimes.size()];
        for (int i = 0; i < mSpeechTimes.size(); i++)
            speechTimes[i] = mSpeechTimes.get(i);
        bundle.putLongArray(key + BUNDLE_SUFFIX_SPEECH_TIMES, speechTimes);

        mSpeechManager.saveState(key + BUNDLE_SUFFIX_SPEECH, bundle);
    }

    /**
     * Restores the state of this <code>DebateManager</code> from a {@link Bundle}.
     * @param key A String to uniquely distinguish this <code>DebateManager</code> from any other
     *        objects that might be stored in the same Bundle.
     * @param bundle The Bundle from which to restore this information.
     */
    public void restoreState(String key, Bundle bundle) {

        // Restore the current speech
        mCurrentSpeechIndex = bundle.getInt(key + BUNDLE_SUFFIX_INDEX, 0);
        loadSpeech();

        // If there are saved speech times, restore them as well
        long[] speechTimes = bundle.getLongArray(key + BUNDLE_SUFFIX_SPEECH_TIMES);
        if (speechTimes != null)
            for (int i = 0; i < speechTimes.length; i++)
                mSpeechTimes.set(i, speechTimes[i]);

        mSpeechManager.restoreState(key + BUNDLE_SUFFIX_SPEECH, bundle);
    }

    /**
     * Cleans up, should be called before deleting.
     */
    public void release() {
        stopTimer();
    }

    //******************************************************************************************
    // Private methods
    //******************************************************************************************

    private void saveSpeech() {
        mSpeechTimes.set(mCurrentSpeechIndex, mSpeechManager.getCurrentTime());
    }

    private void loadSpeech() {
        mSpeechManager.loadSpeech(mDebateFormat.getSpeechFormat(mCurrentSpeechIndex),
                mSpeechTimes.get(mCurrentSpeechIndex));
    }

}

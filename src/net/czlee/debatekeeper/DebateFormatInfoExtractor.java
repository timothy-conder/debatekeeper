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

import java.io.IOException;
import java.io.InputStream;

import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;

import android.content.Context;
import android.util.Log;
import android.util.Xml;
import android.util.Xml.Encoding;

/**
 * TODO Comment this class, before it's too late!
 * Extracts information form an XML file and returns a DebateFormatInfo object.
 *
 * @author Chuan-Zheng Lee
 * @since  2012-06-20
 */
public class DebateFormatInfoExtractor {

    private final Context          mContext;
    private final String           DEBATING_TIMER_URI;
    private DebateFormatInfo mDfi;

    public DebateFormatInfoExtractor(Context context) {
        mContext           = context;
        DEBATING_TIMER_URI = context.getString(R.string.XmlUri);
    }

    // ******************************************************************************************
    // Private classes
    // ******************************************************************************************

    private class DebateFormatInfoContentHandler implements ContentHandler {

        private boolean mIsInRootContext        = false;
        private boolean mDescriptionFound       = false;
        private String  mThirdLevelInfoContext  = null;
        private String  mCharactersBuffer       = null;
        private String  mCurrentSpeechFormatRef = null;
        private String  mCurrentResourceRef     = null;

        private DebateFormatXmlSecondLevelContext mCurrentSecondLevelContext
                = DebateFormatXmlSecondLevelContext.NONE;

        @Override public void endDocument() throws SAXException {}
        @Override public void endPrefixMapping(String prefix) throws SAXException {}
        @Override public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {}
        @Override public void processingInstruction(String target, String data) throws SAXException {}
        @Override public void setDocumentLocator(Locator locator) {}
        @Override public void skippedEntity(String name) throws SAXException {}
        @Override public void startDocument() throws SAXException {}
        @Override public void startPrefixMapping(String prefix, String uri) throws SAXException {}


        @Override
        public void characters(char[] ch, int start, int length)
                throws SAXException {
            String str = new String(ch, start, length);
            if (mCharactersBuffer == null)
                return;
            mCharactersBuffer = mCharactersBuffer.concat(str);
        }

        @Override
        public void endElement(String uri, String localName, String qName)
                throws SAXException {
            /**
             * <debateformat name="something" schemaversion="1.0">
             * End the root context.
             */
            if (areEqual(localName, R.string.XmlElemNameRoot)) {
                mIsInRootContext = false;
                return;
            }

            /**
             * <info>, <resource>, <speechtype>, <speeches>
             * End the second-level context.
             */
            if (areEqual(localName, R.string.XmlElemNameInfo) ||
                areEqual(localName, R.string.XmlElemNameResource) ||
                areEqual(localName, R.string.XmlElemNameSpeechFormat) ||
                areEqual(localName, R.string.XmlElemNameSpeechesList))
                    mCurrentSecondLevelContext = DebateFormatXmlSecondLevelContext.NONE;

            if (getCurrentSecondLevelContext() == DebateFormatXmlSecondLevelContext.INFO) {
                if (localName.equals(mThirdLevelInfoContext)) {
                    if (mCharactersBuffer == null) {
                        Log.e(this.getClass().getSimpleName(), "In a third level context but mCharactersBuffer is empty");
                        return;
                    }
                    // <region>
                    if (areEqual(localName, R.string.XmlElemNameInfoRegion)) {
                        mDfi.addRegion(mCharactersBuffer);
                    // <level>
                    } else if (areEqual(localName, R.string.XmlElemNameInfoLevel)) {
                        mDfi.addLevel(mCharactersBuffer);
                    // <usedat>
                    } else if (areEqual(localName, R.string.XmlElemNameInfoUsedAt)) {
                        mDfi.addUsedAt(mCharactersBuffer);
                    // <desc>
                    } else if (areEqual(localName, R.string.XmlElemNameInfoDesc)) {
                        if (!mDescriptionFound) {
                            mDescriptionFound = true;
                            mDfi.setDescription(mCharactersBuffer);
                        }
                    }
                    mThirdLevelInfoContext = null; // end this context
                    mCharactersBuffer = null;
                }
            }
        }

        @Override
        public void startElement(String uri, String localName, String qName,
                Attributes atts) throws SAXException {

            if (!uri.equals(DEBATING_TIMER_URI))
                return;

            /**
             * <debateformat name="something" schemaversion="1.0">
             */
            if (areEqual(localName, R.string.XmlElemNameRoot)) {
                String name = getValue(atts, R.string.XmlAttrNameRootName);
                if (name != null)
                    mDfi.setName(name);
                mIsInRootContext = true;
                return;
            }

            // For everything else, we must be inside the root element.
            // If we're not, refuse to do anything.
            if (!mIsInRootContext) {
                return;
            }

            /**
             * <info>
             */
            if (areEqual(localName, R.string.XmlElemNameInfo)) {
                mCurrentSecondLevelContext = DebateFormatXmlSecondLevelContext.INFO;
                return;

            // Inside the <info> tags
            } else if (getCurrentSecondLevelContext() == DebateFormatXmlSecondLevelContext.INFO) {
                mThirdLevelInfoContext = localName;
                mCharactersBuffer = new String();

            /**
             * <resource ref="string">
             */
            } else if (areEqual(localName, R.string.XmlElemNameResource)) {

                // Ignore if any of the following are true:
                //  1. No reference is given
                //  2. We're already inside a second-level context
                //  3. This resource already exists
                String reference = getValue(atts, R.string.XmlAttrNameCommonRef);
                if (reference == null)
                    return;
                if (!assertNotInsideAnySecondLevelContextAndResetOtherwise())
                    return;
                if (mDfi.hasResource(reference))
                    return;

                mCurrentSecondLevelContext = DebateFormatXmlSecondLevelContext.RESOURCE;
                mCurrentResourceRef        = reference;
                mDfi.addResource(reference);

            /**
             * <speechtype ref="string" length="5:00">
             */
            } else if (areEqual(localName, R.string.XmlElemNameSpeechFormat)) {

                // Ignore if any of the following are true:
                //  1. No reference is given
                //  2. We're already inside a second-level context
                //  3. This speech format already exists
                //  4. No length is given or the length is invalid
                String reference = getValue(atts, R.string.XmlAttrNameCommonRef);
                if (reference == null)
                    return;
                if (!assertNotInsideAnySecondLevelContextAndResetOtherwise())
                    return;
                if (mDfi.hasSpeechFormat(reference))
                    return;

                String lengthStr = getValue(atts, R.string.XmlAttrNameSpeechFormatLength);
                long length;
                if (lengthStr == null)
                    return;
                try {
                    length = timeStr2Secs(lengthStr);
                } catch (NumberFormatException e) {
                    return;
                }

                mCurrentSecondLevelContext = DebateFormatXmlSecondLevelContext.SPEECH_FORMAT;
                mCurrentSpeechFormatRef    = reference;
                mDfi.addSpeechFormat(reference, length);

            /**
             * <bell time="1:00" pauseonbell="true">
             */
            } else if (areEqual(localName, R.string.XmlElemNameBell)) {

                // Ignore if any of the following are true:
                //  1. No time is given or the time is invalid
                //  2. We are not in an applicable second-level context

                String timeStr = getValue(atts, R.string.XmlAttrNameBellTime);;
                long time = 0;
                boolean isFinish = false;
                if (timeStr == null)
                    return;
                else if (areEqualIgnoringCase(timeStr, R.string.XmlAttrValueBellTimeFinish))
                    isFinish = true;
                else {
                    try {
                        time = timeStr2Secs(timeStr);
                    } catch (NumberFormatException e) {
                        return;
                    }
                }

                // (We also need to check if this is a pause-on-bell.)
                boolean pause = false;
                String pauseOnBellStr = getValue(atts, R.string.XmlAttrNameBellPauseOnBell);
                if (pauseOnBellStr != null) {
                    if (areEqualIgnoringCase(pauseOnBellStr, R.string.XmlAttrValueCommonTrue))
                        pause = true;
                }

                switch (getCurrentSecondLevelContext()) {
                case SPEECH_FORMAT:
                    if (mCurrentSpeechFormatRef == null)
                        return;
                    if (isFinish)
                        mDfi.addFinishBellToSpeechFormat(pause, mCurrentSpeechFormatRef);
                    else
                        mDfi.addBellToSpeechFormat(time, pause, mCurrentSpeechFormatRef);
                    break;
                case RESOURCE:
                    if (mCurrentResourceRef == null)
                        return;
                    mDfi.addBellToResource(time, pause, mCurrentResourceRef);
                }

            /**
             * <include resource="reference">
             */
            } else if (areEqual(localName, R.string.XmlElemNameInclude)) {

                // Ignore if any of the following are true:
                //  1. No resource reference is given
                //  2. We're not inside a speech format

                String resourceRef = getValue(atts, R.string.XmlAttrNameIncludeResource);
                if (resourceRef == null)
                    return;
                if (getCurrentSecondLevelContext() != DebateFormatXmlSecondLevelContext.SPEECH_FORMAT)
                    return;

                if (mCurrentSpeechFormatRef != null)
                    mDfi.includeResource(mCurrentSpeechFormatRef, resourceRef);

            /**
             * <speeches>
             */
            } else if (areEqual(localName, R.string.XmlElemNameSpeechesList)) {
                // Ignore if we're already inside a second-level context
                if (!assertNotInsideAnySecondLevelContextAndResetOtherwise())
                    return;
                mCurrentSecondLevelContext = DebateFormatXmlSecondLevelContext.SPEECHES_LIST;

            /**
            * <speech name="1st Affirmative" type="formatname">
            */
            } else if (areEqual(localName, R.string.XmlElemNameSpeech)) {

                // Ignore if any of the following are true:
                //  1. No name is given
                //  2. No format is given
                //  3. We are not inside the speeches list

                String name = getValue(atts, R.string.XmlAttrNameSpeechName);
                String format = getValue(atts, R.string.XmlAttrNameSpeechFormat);
                if (name == null || format == null)
                    return;
                if (getCurrentSecondLevelContext() != DebateFormatXmlSecondLevelContext.SPEECHES_LIST)
                    return;

                mDfi.addSpeech(name, format);

            }
        }

        private boolean assertNotInsideAnySecondLevelContextAndResetOtherwise() {
            if (getCurrentSecondLevelContext() != DebateFormatXmlSecondLevelContext.NONE) {
                mCurrentResourceRef = null;
                mCurrentSpeechFormatRef = null;
                return false;
            }
            return true;
        }

        private boolean areEqual(String string, int resid) {
            return string.equals(getString(resid));
        }

        private boolean areEqualIgnoringCase(String string, int resid) {
            return string.equalsIgnoreCase(getString(resid));
        }

        private String getString(int resid) {
            return mContext.getString(resid);
        }

        private String getValue(Attributes atts, int localNameResid) {
            return atts.getValue(DEBATING_TIMER_URI, getString(localNameResid));
        }

        private DebateFormatXmlSecondLevelContext getCurrentSecondLevelContext() {
            return mCurrentSecondLevelContext;
        }

    }

    // ******************************************************************************************
    // Public methods
    // ******************************************************************************************

    /**
     * Gets the debate
     * @param is an <code>InputStream</code> for an XML file to parse
     * @return the DebateFormatInfo object
     * @throws IOException if thrown by the attempt to use the <code>InputStream</code>
     * @throws SAXException if thrown by the XML parser (SAX)
     */
    public DebateFormatInfo getDebateFormatInfo(InputStream is) throws IOException, SAXException {

        mDfi = new DebateFormatInfo(mContext);
        Xml.parse(is, Encoding.UTF_8, new DebateFormatInfoContentHandler());
        return mDfi;
    }

    // ******************************************************************************************
    // Public methods
    // ******************************************************************************************

    /**
     * Converts a String in the format 00:00 to a long, being the number of seconds
     * @param s the String
     * @return the total number of seconds (minutes + seconds * 60)
     * @throws NumberFormatException
     */
    private static long timeStr2Secs(String s) throws NumberFormatException {
        long seconds = 0;
        String parts[] = s.split(":", 2);
        switch (parts.length){
        case 2:
            long minutes = Long.parseLong(parts[0]);
            seconds += minutes * 60;
            seconds += Long.parseLong(parts[1]);
            break;
        case 1:
            seconds = Long.parseLong(parts[0]);
            break;
        default:
            throw new NumberFormatException();
        }
        return seconds;
    }

}

/*
 * Copyright (C) 2025 AKS-Labs (original author)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.akslabs.circletosearch

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognitionService
import android.util.Xml
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

@RunWith(AndroidJUnit4::class)
class AssistantServiceManifestInstrumentedTest {
    @Test
    fun voiceServiceComponentAlsoResolvesProtectedAssistFallback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageManager = context.packageManager
        val voiceComponent = ComponentName(context, CircleToSearchVoiceService::class.java)

        val serviceInfo = packageManager.getServiceInfo(voiceComponent, 0)
        assertEquals(CircleToSearchVoiceService::class.java.name, serviceInfo.name)

        val aliasInfo = packageManager.getActivityInfo(voiceComponent, 0)
        assertEquals(AssistantFallbackActivity::class.java.name, aliasInfo.targetActivity)
        assertEquals(
            "android.permission.ACCESS_VOICE_INTERACTION_SERVICE",
            aliasInfo.permission,
        )
        assertTrue(aliasInfo.exported)

        val resolvedFallback = packageManager.resolveActivity(
            Intent(Intent.ACTION_ASSIST).setComponent(voiceComponent),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        assertNotNull(resolvedFallback)
    }

    @Test
    fun recognitionServicePublishesValidSystemMetadata() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val serviceInfo = context.packageManager.getServiceInfo(
            ComponentName(context, CircleToSearchRecognitionService::class.java),
            PackageManager.GET_META_DATA,
        )

        val metadataResource = serviceInfo.metaData.getInt(RecognitionService.SERVICE_META_DATA)
        assertNotEquals("android.speech metadata must reference XML", 0, metadataResource)

        context.resources.getXml(metadataResource).use { parser ->
            while (
                parser.eventType != XmlPullParser.START_TAG &&
                parser.eventType != XmlPullParser.END_DOCUMENT
            ) {
                parser.next()
            }

            assertEquals("recognition-service", parser.name)
            val attributes = Xml.asAttributeSet(parser)
            assertFalse(
                "The assistant stub must not replace the user's speech recognizer",
                attributes.getAttributeBooleanValue(
                    "http://schemas.android.com/apk/res/android",
                    "selectableAsDefault",
                    true,
                ),
            )
        }
    }
}

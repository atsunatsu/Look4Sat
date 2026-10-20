package com.rtbishop.look4sat.core.data.repository

import android.content.SharedPreferences
import com.rtbishop.look4sat.core.domain.repository.CwTransmitPresets
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class RemoteCwPresetSettingsTest {
    @Test fun everyPresetPersistsIndependentlyAndReloads() {
        val values=mutableMapOf<String,String>()
        val prefs=preferences(values)
        assertEquals(CwTransmitPresets.defaults,readRemoteCwPresets(prefs))
        for(index in 0..7) writeRemoteCwPreset(prefs,index," cq f${index+1} ")
        assertEquals((1..8).map {"CQ F$it"},readRemoteCwPresets(preferences(values)))
        writeRemoteCwPreset(prefs,3,"")
        assertEquals("",readRemoteCwPresets(preferences(values))[3])
        assertEquals("CQ F1",readRemoteCwPresets(prefs)[0])
    }
    @Test fun invalidTextOrSlotCannotOverwriteSavedPreset() {
        val values=mutableMapOf<String,String>();val prefs=preferences(values)
        writeRemoteCwPreset(prefs,0,"CQ TEST")
        assertThrows(IllegalArgumentException::class.java) {writeRemoteCwPreset(prefs,0,"中文")}
        assertThrows(IllegalArgumentException::class.java) {writeRemoteCwPreset(prefs,8,"CQ")}
        assertEquals("CQ TEST",readRemoteCwPresets(prefs)[0])
        values["remoteCwPreset1"]="中文"
        assertEquals(CwTransmitPresets.defaults[1],readRemoteCwPresets(prefs)[1])
    }
    private fun preferences(values: MutableMap<String,String>): SharedPreferences {
        val editor=Proxy.newProxyInstance(javaClass.classLoader,arrayOf(SharedPreferences.Editor::class.java)) { proxy,method,args ->
            when(method.name) {
                "putString"->{values[args!![0] as String]=args[1] as String;proxy}
                "apply"->null
                "commit"->true
                else->throw UnsupportedOperationException(method.name)
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(javaClass.classLoader,arrayOf(SharedPreferences::class.java)) { _,method,args ->
            when(method.name) {
                "getString"->values[args!![0]] ?: args[1]
                "edit"->editor
                else->throw UnsupportedOperationException(method.name)
            }
        } as SharedPreferences
    }
}

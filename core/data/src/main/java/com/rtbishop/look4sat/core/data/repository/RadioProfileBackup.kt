package com.rtbishop.look4sat.core.data.repository

import com.rtbishop.look4sat.core.domain.repository.*
import org.json.JSONArray
import org.json.JSONObject

internal object RadioProfileBackup {
    fun encode(profiles:List<StoredRadioProfile>,generation:Long):String {
        val records=JSONArray()
        profiles.forEach {profile->
            val item=JSONObject().put("record",profile.rawHex).put("slot",profile.slot)
            profile.alias?.let {item.put("alias",it)}
            profile.metadata?.let {item.put("satelliteName",it.satelliteName).put("transponderUuid",it.transponderUuid).put("description",it.description)}
            records.put(item)
        }
        return JSONObject().put("format","Look4Sat-L4S-profiles").put("version",1).put("family",3)
            .put("generation",generation).put("profiles",records).toString(2)
    }
    fun decode(text:String):List<StoredRadioProfile> {
        require(text.toByteArray(Charsets.UTF_8).size<=262144){"Backup exceeds 256 KiB"}
        var depth=0;var quoted=false;var escaped=false
        text.forEach {ch->
            if(quoted) {
                if(escaped)escaped=false else if(ch=='\\')escaped=true else if(ch=='"')quoted=false
            } else when(ch) {
                '"'->quoted=true
                '{','['->{depth++;require(depth<=16){"Backup JSON nesting exceeds limit"}}
                '}',']'->{depth--;require(depth>=0){"Malformed backup JSON"}}
            }
        }
        require(depth==0 && !quoted){"Malformed backup JSON"}
        val root=JSONObject(text)
        require(root.getString("format")=="Look4Sat-L4S-profiles" && root.getInt("version")==1 && root.getInt("family")==3){"Unsupported profile backup"}
        val list=root.getJSONArray("profiles");require(list.length()<=56){"Too many profiles"}
        val profiles=(0 until list.length()).map {index->
            val item=list.getJSONObject(index)
            val record=RadioProfileRecordCodec.decode(index,RadioProfileRecordCodec.bytes(item.getString("record")))
            require(record!=null && record.compatible){"Backup contains an unsupported or damaged profile"}
            val metadata=if(item.has("satelliteName") && item.has("transponderUuid"))
                RadioProfileMetadata(item.getString("satelliteName"),item.getString("transponderUuid"),item.optString("description")) else null
            require(metadata==null || radioSatelliteProfileKey(metadata.satelliteName,metadata.transponderUuid)==record.key){"Profile name/identity mismatch"}
            record.copy(metadata=metadata,alias=item.optString("alias").takeIf {it.isNotBlank()}?.take(128))
        }
        require(profiles.map {it.key}.distinct().size==profiles.size){"Duplicate profile identities"}
        return profiles
    }
}

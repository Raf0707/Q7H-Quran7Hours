package raf.console.quran7hours.media

import android.content.Context
import raf.console.quran7hours.data.QuranCatalog

/** Commands are committed before MediaController changes; background service sees exact range. */
data class Q7HHadrRange(val segment: QuranCatalog.Segment, val from:Int,val to:Int,val mode:String)
data class Q7HAyahRange(val fromSurah:Int,val fromAyah:Int,val toSurah:Int,val toAyah:Int)

object Q7HRangeState {
    private fun p(context: Context) = context.applicationContext.getSharedPreferences("q7h_compose",Context.MODE_PRIVATE)
    fun hadr(context: Context, segment: QuranCatalog.Segment, from: Int, to: Int, mode: String): Boolean {
        if (from < 1 || to < from || mode !in listOf("NEXT","STOP","REPEAT","REPEAT_N")) return false
        val preferences=p(context)
        val generation=preferences.getInt("q7h_range_hadr_generation",0)+1
        return preferences.edit()
            .putInt("q7h_range_hadr_generation",generation)
            .putString("q7h_range_hadr_segment",segment.name)
            .putInt("q7h_range_hadr_start",from)
            .putInt("q7h_range_hadr_end",to)
            .putString("q7h_range_hadr_mode",mode)
            .putBoolean("q7h_range_hadr_enabled",true).commit()
    }
    fun hadrRange(context: Context): Q7HHadrRange? {
        val prefs=p(context)
        if(!prefs.getBoolean("q7h_range_hadr_enabled",false)) return null
        val segment=runCatching { QuranCatalog.Segment.valueOf(
            prefs.getString("q7h_range_hadr_segment","") ?: "") }.getOrNull() ?: return null
        return Q7HHadrRange(segment,prefs.getInt("q7h_range_hadr_start",1),
            prefs.getInt("q7h_range_hadr_end",1),prefs.getString("q7h_range_hadr_mode","STOP") ?: "STOP")
    }

    private fun key(base:String, channel:AudioChannel):String =
        if (channel == AudioChannel.HAFIZ) base.replace("q7h_range_ayah_","q7h_range_hafiz_")
            .replace("q7h_ayah_seek_","q7h_hafiz_seek_") else base
    fun ayahRange(context: Context, channel:AudioChannel = AudioChannel.AYAH):Q7HAyahRange? {
        val prefs=p(context)
        if(!prefs.getBoolean(key("q7h_range_ayah_enabled",channel),false)) return null
        val fromS=prefs.getInt(key("q7h_range_ayah_start_s",channel),-1)
        val fromA=prefs.getInt(key("q7h_range_ayah_start_a",channel),-1)
        val toS=prefs.getInt(key("q7h_range_ayah_end_s",channel),-1)
        val toA=prefs.getInt(key("q7h_range_ayah_end_a",channel),-1)
        if(!QuranCatalog.isValidAyah(fromS,fromA) || !QuranCatalog.isValidAyah(toS,toA)) return null
        return Q7HAyahRange(fromS,fromA,toS,toA)
    }

    /** Seek within an existing Ayah range; service preserves original A/B endpoints. */
    fun requestAyahSeek(context:Context,surah:Int,ayah:Int,channel:AudioChannel = AudioChannel.AYAH):Boolean {
        val r=ayahRange(context,channel) ?: return false
        if(!QuranCatalog.isValidAyah(surah,ayah)) return false
        if ((surah<r.fromSurah || surah==r.fromSurah && ayah<r.fromAyah) ||
            (surah>r.toSurah || surah==r.toSurah && ayah>r.toAyah)) return false
        val prefs=p(context)
        return prefs.edit()
            .putInt(key("q7h_ayah_seek_target_s",channel),surah)
            .putInt(key("q7h_ayah_seek_target_a",channel),ayah)
            .putInt(key("q7h_ayah_seek_generation",channel),prefs.getInt(key("q7h_ayah_seek_generation",channel),0)+1)
            .commit()
    }

    fun clearHadr(context: Context) {
        p(context).edit().putBoolean("q7h_range_hadr_enabled",false).apply()
    }
    fun ayah(context: Context, surah: Int, verse: Int, endSurah: Int, endVerse: Int, mode: AyahEndMode, channel:AudioChannel = AudioChannel.AYAH): Boolean {
        if (!QuranCatalog.isValidAyah(surah,verse) || !QuranCatalog.isValidAyah(endSurah,endVerse) ||
            endSurah < surah || endSurah == surah && endVerse < verse) return false
        return p(context).edit()
            .putInt(key("q7h_range_ayah_start_s",channel),surah)
            .putInt(key("q7h_range_ayah_start_a",channel),verse)
            .putInt(key("q7h_range_ayah_end_s",channel),endSurah)
            .putInt(key("q7h_range_ayah_end_a",channel),endVerse)
            .putBoolean(key("q7h_range_ayah_enabled",channel),true)
            .putString(if(channel==AudioChannel.HAFIZ) "hafizEndMode" else "ayahEndMode",mode.name).commit()
    }
    fun clearAyah(context: Context, channel:AudioChannel = AudioChannel.AYAH) {
        p(context).edit().putBoolean(key("q7h_range_ayah_enabled",channel),false).apply()
    }
    fun ayahActive(context: Context, channel:AudioChannel = AudioChannel.AYAH) = p(context).getBoolean(key("q7h_range_ayah_enabled",channel),false)
}

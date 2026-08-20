package com.v2ray.ang.senpai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class IspProfile(
    val name: String,
    val goodCidrs: List<String>,   // رنج‌هایی که پینگ دادن
    val createdAt: Long = System.currentTimeMillis(),
)

object IspManager {
    private const val PREF_FILE = "isp_profiles"
    private const val KEY_PROFILES = "profiles"

    fun saveProfile(ctx: Context, profile: IspProfile) {
        val prefs = ctx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val all = loadAll(ctx).toMutableList()
        all.removeAll { it.name.equals(profile.name, ignoreCase = true) }
        all.add(profile)
        val arr = JSONArray()
        for (p in all) {
            val obj = JSONObject()
            obj.put("name", p.name)
            obj.put("createdAt", p.createdAt)
            val cidrs = JSONArray()
            p.goodCidrs.forEach { cidrs.put(it) }
            obj.put("goodCidrs", cidrs)
            arr.put(obj)
        }
        prefs.edit().putString(KEY_PROFILES, arr.toString()).apply()
    }

    fun loadAll(ctx: Context): List<IspProfile> {
        val prefs = ctx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val cidrs = mutableListOf<String>()
                val ca = obj.getJSONArray("goodCidrs")
                for (j in 0 until ca.length()) cidrs.add(ca.getString(j))
                IspProfile(
                    name = obj.getString("name"),
                    goodCidrs = cidrs,
                    createdAt = obj.optLong("createdAt", 0L),
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    fun getProfile(ctx: Context, name: String): IspProfile? =
        loadAll(ctx).find { it.name.equals(name, ignoreCase = true) }

    fun deleteProfile(ctx: Context, name: String) {
        val prefs = ctx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val all = loadAll(ctx).filter { !it.name.equals(name, ignoreCase = true) }
        val arr = JSONArray()
        for (p in all) {
            val obj = JSONObject()
            obj.put("name", p.name)
            obj.put("createdAt", p.createdAt)
            val cidrs = JSONArray()
            p.goodCidrs.forEach { cidrs.put(it) }
            obj.put("goodCidrs", cidrs)
            arr.put(obj)
        }
        prefs.edit().putString(KEY_PROFILES, arr.toString()).apply()
    }
}

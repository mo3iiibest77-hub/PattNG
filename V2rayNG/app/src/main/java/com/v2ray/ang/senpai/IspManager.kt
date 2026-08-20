package com.v2ray.ang.senpai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class IspProfile(
    val name: String,
    val goodCidrs: List<String>,
    val manualCidrs: List<String> = emptyList(),  // رنج‌های دستی کاربر
    val lastScannedIndex: Int = 0,                // ادامه از اینجا دفعه بعد
    val createdAt: Long = System.currentTimeMillis(),
) {
    val allCidrs: List<String> get() = (manualCidrs + goodCidrs).distinct()
}

object IspManager {
    private const val PREF_FILE = "isp_profiles"
    private const val KEY_PROFILES = "profiles"

    fun saveProfile(ctx: Context, profile: IspProfile) {
        val prefs = ctx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val all = loadAll(ctx).toMutableList()
        all.removeAll { it.name.equals(profile.name, ignoreCase = true) }
        all.add(profile)
        prefs.edit().putString(KEY_PROFILES, serialize(all)).apply()
    }

    fun addManualCidr(ctx: Context, ispName: String, cidr: String) {
        val all = loadAll(ctx).toMutableList()
        val idx = all.indexOfFirst { it.name.equals(ispName, ignoreCase = true) }
        if (idx >= 0) {
            val p = all[idx]
            if (!p.manualCidrs.contains(cidr.trim())) {
                all[idx] = p.copy(manualCidrs = p.manualCidrs + cidr.trim())
            }
        } else {
            all.add(IspProfile(name = ispName, goodCidrs = emptyList(), manualCidrs = listOf(cidr.trim())))
        }
        ctx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_PROFILES, serialize(all)).apply()
    }

    fun removeManualCidr(ctx: Context, ispName: String, cidr: String) {
        val all = loadAll(ctx).toMutableList()
        val idx = all.indexOfFirst { it.name.equals(ispName, ignoreCase = true) }
        if (idx >= 0) {
            all[idx] = all[idx].copy(manualCidrs = all[idx].manualCidrs.filter { it != cidr })
            ctx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                .edit().putString(KEY_PROFILES, serialize(all)).apply()
        }
    }

    fun savePartialProgress(ctx: Context, ispName: String, foundCidrs: List<String>, lastIndex: Int) {
        val all = loadAll(ctx).toMutableList()
        val idx = all.indexOfFirst { it.name.equals(ispName, ignoreCase = true) }
        if (idx >= 0) {
            val p = all[idx]
            val merged = (p.goodCidrs + foundCidrs).distinct()
            all[idx] = p.copy(goodCidrs = merged, lastScannedIndex = lastIndex)
        } else {
            all.add(IspProfile(name = ispName, goodCidrs = foundCidrs, lastScannedIndex = lastIndex))
        }
        ctx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_PROFILES, serialize(all)).apply()
    }

    fun loadAll(ctx: Context): List<IspProfile> {
        val prefs = ctx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                IspProfile(
                    name             = obj.getString("name"),
                    goodCidrs        = obj.getJSONArray("goodCidrs").let { a -> (0 until a.length()).map { a.getString(it) } },
                    manualCidrs      = obj.optJSONArray("manualCidrs")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                    lastScannedIndex = obj.optInt("lastScannedIndex", 0),
                    createdAt        = obj.optLong("createdAt", 0L),
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    fun getProfile(ctx: Context, name: String): IspProfile? =
        loadAll(ctx).find { it.name.equals(name, ignoreCase = true) }

    fun deleteProfile(ctx: Context, name: String) {
        val all = loadAll(ctx).filter { !it.name.equals(name, ignoreCase = true) }
        ctx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_PROFILES, serialize(all)).apply()
    }

    private fun serialize(list: List<IspProfile>): String {
        val arr = JSONArray()
        for (p in list) {
            val obj = JSONObject()
            obj.put("name", p.name)
            obj.put("createdAt", p.createdAt)
            obj.put("lastScannedIndex", p.lastScannedIndex)
            val cidrs = JSONArray(); p.goodCidrs.forEach { cidrs.put(it) }
            obj.put("goodCidrs", cidrs)
            val manual = JSONArray(); p.manualCidrs.forEach { manual.put(it) }
            obj.put("manualCidrs", manual)
            arr.put(obj)
        }
        return arr.toString()
    }
}

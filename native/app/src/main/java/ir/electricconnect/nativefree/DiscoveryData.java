package ir.electricconnect.nativefree;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Additional tables in the existing database. Never replaces records or TDLib files. */
final class DiscoveryData {
    final MainActivity.Store store;
    DiscoveryData(MainActivity.Store store) { this.store=store; }
    static void create(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS discovery_state (name TEXT PRIMARY KEY, value TEXT NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS discovery_urls (url TEXT PRIMARY KEY, state TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, updated INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS public_groups (id TEXT PRIMARY KEY, title TEXT NOT NULL, source TEXT NOT NULL, found INTEGER NOT NULL, seen INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS imported_groups (lookup_key TEXT PRIMARY KEY, title TEXT NOT NULL, chat_id TEXT NOT NULL DEFAULT '', public_link TEXT NOT NULL DEFAULT '', source TEXT NOT NULL DEFAULT '', added INTEGER NOT NULL)");
    }
    synchronized void put(String name,JSONObject value) {
        ContentValues v=new ContentValues();v.put("name",name);v.put("value",value.toString());
        store.getWritableDatabase().insertWithOnConflict("discovery_state",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }
    synchronized JSONObject get(String name) {
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT value FROM discovery_state WHERE name=?",new String[]{name})) {
            if(c.moveToFirst())return new JSONObject(c.getString(0));
        } catch(org.json.JSONException ignored) { }
        return new JSONObject();
    }
    void renewOldPages() {
        renewOldPages(java.util.concurrent.TimeUnit.HOURS.toMillis(24));
    }
    /** Retry previously successful and temporary-failure sources only, never denied pages. */
    void renewOldPages(long minimumAgeMillis) {
        store.getWritableDatabase().execSQL(
            "UPDATE discovery_urls SET state='pending', attempts=0 WHERE updated<? AND state IN ('done','retry','interrupted')",
            new Object[]{System.currentTimeMillis()-minimumAgeMillis});
    }
    boolean queue(String url) {
        ContentValues v=new ContentValues();v.put("url",url);v.put("state","pending");v.put("attempts",0);v.put("updated",System.currentTimeMillis());
        return store.getWritableDatabase().insertWithOnConflict("discovery_urls",null,v,SQLiteDatabase.CONFLICT_IGNORE)!=-1;
    }
    List<String> pending(int limit) {
        List<String> out=new ArrayList<>();
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT url FROM discovery_urls WHERE state IN ('pending','interrupted','retry') AND attempts<3 ORDER BY updated,url LIMIT ?",new String[]{""+limit})) {
            while(c.moveToNext())out.add(c.getString(0));
        }
        return out;
    }
    void finishUrl(String url,String state) {
        store.getWritableDatabase().execSQL("UPDATE discovery_urls SET state=?,attempts=attempts+1,updated=? WHERE url=?",new Object[]{state,System.currentTimeMillis(),url});
    }
    synchronized boolean saveGroup(TdlibEngine.ChatOverview group,String query) {
        long now=System.currentTimeMillis();
        ContentValues v=new ContentValues();v.put("id",Long.toString(group.id));v.put("title",group.name);
        v.put("source",query);v.put("found",now);v.put("seen",now);
        boolean fresh=store.getWritableDatabase().insertWithOnConflict("public_groups",null,v,SQLiteDatabase.CONFLICT_IGNORE)!=-1;
        if(!fresh) {v.remove("found");store.getWritableDatabase().update("public_groups",v,"id=?",new String[]{Long.toString(group.id)});}
        return fresh;
    }
    List<TdlibEngine.ChatOverview> groups() {
        List<TdlibEngine.ChatOverview> out=new ArrayList<>();
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT id,title FROM public_groups ORDER BY seen DESC",null)) {
            while(c.moveToNext())out.add(new TdlibEngine.ChatOverview(Long.parseLong(c.getString(0)),c.getString(1),"گروه عمومی",false));
        }
        return out;
    }
    /** All checked or pending public website URLs, including source status. */
    List<String[]> webExport() {
        List<String[]> out=new ArrayList<>();
        try(Cursor c=store.getReadableDatabase().rawQuery(
                "SELECT url,state,attempts,updated FROM discovery_urls ORDER BY updated DESC",null)){
            while(c.moveToNext())out.add(new String[]{
                c.getString(0),c.getString(1),Integer.toString(c.getInt(2)),
                Long.toString(c.getLong(3))});
        }
        return out;
    }
    long total(String table) {
        if(!"public_groups".equals(table)&&!"discovery_urls".equals(table))return 0;
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM "+table,null)){
            return c.moveToFirst()?c.getLong(0):0;
        }
    }
    long finishedWebPages() {
        try(Cursor c=store.getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM discovery_urls WHERE state='done'",null)){
            return c.moveToFirst()?c.getLong(0):0;
        }
    }
    long importedGroupCount() {
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM imported_groups",null)){
            return c.moveToFirst()?c.getLong(0):0;
        }
    }
    /** Columns: name, chat id (if available), public link, source, created. */
    List<String[]> importedGroupExport() {
        List<String[]> rows=new ArrayList<>();
        try(Cursor c=store.getReadableDatabase().rawQuery(
            "SELECT title,chat_id,public_link,source,added FROM imported_groups ORDER BY added DESC",null)){
            while(c.moveToNext())rows.add(new String[]{
                c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4)});
        }
        return rows;
    }
    List<String[]> groupExport() {
        List<String[]> out=new ArrayList<>();
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT title,id,source,found,seen FROM public_groups ORDER BY seen DESC",null)) {
            while(c.moveToNext())out.add(new String[]{c.getString(0),c.getString(1),"عمومی کشف‌شده؛ عضویت تأیید نشده", "جست‌وجوی عمومی تلگرام: "+c.getString(2),c.getString(3),c.getString(4)});
        }
        return out;
    }
}

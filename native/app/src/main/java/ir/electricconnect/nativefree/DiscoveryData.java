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
        db.execSQL("CREATE TABLE IF NOT EXISTS imported_web_sources (url TEXT PRIMARY KEY, title TEXT NOT NULL DEFAULT '', added INTEGER NOT NULL)");
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
    /** Read only work belonging to chosen, validated hostnames; older hosts
     * cannot bury newly imported Excel sources after 100+ pending URLs. */
    List<String> pendingForHosts(java.util.Set<String> hosts,int limit) {
        List<String> out=new ArrayList<>();
        if(hosts==null || hosts.isEmpty() || limit<=0)return out;
        // Host matching uses URL prefixes and excludes a hostname suffix trick.
        StringBuilder where=new StringBuilder();
        List<String> arguments=new ArrayList<>();
        for(String host:hosts){
            if(where.length()>0)where.append(" OR ");
            // PublicHttp.validate already restricts hosts to DNS names without
            // LIKE wildcards; avoid a multi-character SQLite ESCAPE literal.
            where.append("(url LIKE ? OR url=?)");
            arguments.add("https://"+host+"/%");
            arguments.add("https://"+host+"/");
        }
        arguments.add(Integer.toString(limit));
        String sql="SELECT url FROM discovery_urls WHERE state IN ('pending','interrupted','retry') AND attempts<3"
            +" AND ("+where+") ORDER BY attempts,updated,url LIMIT ?";
        try(Cursor c=store.getReadableDatabase().rawQuery(sql,arguments.toArray(new String[0]))) {
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

    /** User-imported public business sites are sources, never messaging recipients. */
    static final int MAX_IMPORTED_SOURCES=2000;
    long importedSourceCount() {
        try(Cursor c=store.getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM imported_web_sources",null)) {
            return c.moveToFirst()?c.getLong(0):0;
        }
    }
    /** Cyclic batches keep thousands of Excel sites discoverable without starving later entries. */
    List<String> rotatedImportedSources(int cursor,int limit) {
        int total=(int)importedSourceCount();
        if(total==0||limit<=0)return new ArrayList<>();
        int start=Math.floorMod(cursor,total);
        int needed=Math.min(total,limit);
        List<String> out=new ArrayList<>();
        for(int pass=0;pass<2 && out.size()<needed;pass++) {
            int offset=pass==0?start:0;
            try(Cursor c=store.getReadableDatabase().rawQuery(
                    "SELECT url FROM imported_web_sources ORDER BY added,url LIMIT ? OFFSET ?",
                    new String[]{Integer.toString(needed-out.size()),Integer.toString(offset)})) {
                while(c.moveToNext())out.add(c.getString(0));
            }
        }
        return out;
    }
    /** Returns whether a URL needs processing; already completed/denied pages don't consume a host slot. */
    boolean isPending(String url) {
        try(Cursor c=store.getReadableDatabase().rawQuery(
                "SELECT 1 FROM discovery_urls WHERE url=? AND state IN ('pending','interrupted','retry') AND attempts<3",
                new String[]{url})) {
            return c.moveToFirst();
        }
    }

    List<String[]> importedSourceExport() {
        List<String[]> out=new ArrayList<>();
        try(Cursor c=store.getReadableDatabase().rawQuery(
                "SELECT url,title,added FROM imported_web_sources ORDER BY added,url",null)) {
            while(c.moveToNext())out.add(new String[]{c.getString(0),c.getString(1),c.getString(2)});
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

package ir.electricconnect.nativefree;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** User-selected local backup only. Credentials, sessions and message history are excluded. */
final class LocalBackup {
    static void write(MainActivity.Store store,OutputStream output) throws Exception {
        JSONObject root=new JSONObject().put("format","electric-connect-local-v1");
        root.put("records",table(store,"records",new String[]{"title","phone","category","source","provider","retrieved","created"}));
        root.put("drafts",table(store,"drafts",new String[]{"destination","message","created"}));
        root.put("groups",table(store,"public_groups",new String[]{"id","title","source","found","seen"}));
        root.put("imported_groups",table(store,"imported_groups",new String[]{"lookup_key","title","chat_id","public_link","source","added"}));
        root.put("imported_web_sources",table(store,"imported_web_sources",new String[]{"url","title","added"}));
        JSONObject keywords=new JSONObject();
        keywords.put("buyers",new DiscoveryData(store).get("buyer_keywords").optJSONArray("keywords"));
        keywords.put("groups",new DiscoveryData(store).get("group_keywords").optJSONArray("keywords"));
        root.put("search_keywords",keywords);
        output.write(root.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static JSONArray table(MainActivity.Store store,String table,String[] columns)throws Exception {
        JSONArray rows=new JSONArray();
        try(Cursor c=store.getReadableDatabase().query(table,columns,null,null,null,null,null)) {
            while(c.moveToNext()) {JSONObject row=new JSONObject();for(int i=0;i<columns.length;i++)row.put(columns[i],c.getString(i));rows.put(row);}
        }
        return rows;
    }
    static int restore(MainActivity.Store store,InputStream input)throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
        while((n=input.read(buffer))!=-1) {if(bytes.size()+n>16*1024*1024)throw new IOException();bytes.write(buffer,0,n);}
        JSONObject root=new JSONObject(bytes.toString("UTF-8"));
        if(!"electric-connect-local-v1".equals(root.optString("format")))throw new IOException();
        JSONArray records=root.getJSONArray("records"),drafts=root.getJSONArray("drafts"),groups=root.getJSONArray("groups");
        JSONArray imported=root.optJSONArray("imported_groups");
        if(imported==null)imported=new JSONArray();
        JSONArray webSources=root.optJSONArray("imported_web_sources");
        if(webSources==null)webSources=new JSONArray();
        if(webSources.length()>DiscoveryData.MAX_IMPORTED_SOURCES)throw new IOException("منابع وب در پشتیبان بیش از حد است.");
        JSONObject keywords=root.optJSONObject("search_keywords");
        if(keywords==null)keywords=new JSONObject();
        JSONArray buyerWords=keywords.optJSONArray("buyers"),groupWords=keywords.optJSONArray("groups");
        if(records.length()+drafts.length()+groups.length()+imported.length()+webSources.length()>100000)throw new IOException();
        int count=0;SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();
        try {
            for(int i=0;i<records.length();i++) {
                JSONObject r=records.getJSONObject(i);
                if(r.getString("title").length()>4000||r.getString("source").length()>16000)throw new IOException();
                if(store.insertRecord(r.getString("title"),r.getString("phone"),r.getString("category"),r.getString("source"))) {
                    ContentValues v=new ContentValues();v.put("provider",r.optString("provider","پشتیبان"));v.put("retrieved",r.optLong("retrieved"));v.put("created",r.optLong("created",System.currentTimeMillis()));
                    db.update("records",v,"title=? AND phone=?",new String[]{r.getString("title"),r.getString("phone")});count++;
                }
            }
            for(int i=0;i<drafts.length();i++) {
                JSONObject r=drafts.getJSONObject(i);
                try(Cursor c=db.rawQuery("SELECT 1 FROM drafts WHERE destination=? AND message=?",new String[]{r.getString("destination"),r.getString("message")})) {
                    if(!c.moveToFirst())store.addDraft(r.getString("destination"),r.getString("message"));
                }
            }
            for(int i=0;i<groups.length();i++) {
                JSONObject r=groups.getJSONObject(i);String id=r.getString("id");Long.parseLong(id);
                ContentValues v=new ContentValues();for(String column:new String[]{"id","title","source","found","seen"})v.put(column,r.getString(column));
                db.insertWithOnConflict("public_groups",null,v,SQLiteDatabase.CONFLICT_IGNORE);
            }
            for(int i=0;i<imported.length();i++){
                JSONObject item=imported.getJSONObject(i);
                String key=item.getString("lookup_key");
                String name=item.getString("title");
                String link=item.optString("public_link","");
                String id=item.optString("chat_id","");
                if(key.length()>250 || name.length()>500 || link.length()>200 || id.length()>24)
                    throw new IOException("ردیف گروه ورودی نامعتبر است.");
                ContentValues v=new ContentValues();
                for(String column:new String[]{"lookup_key","title","chat_id","public_link","source","added"})
                    v.put(column,item.optString(column,""));
                db.insertWithOnConflict("imported_groups",null,v,SQLiteDatabase.CONFLICT_IGNORE);
            }
            int knownWebSources=(int)new DiscoveryData(store).importedSourceCount();
            for(int i=0;i<webSources.length();i++) {
                JSONObject row=webSources.getJSONObject(i);
                String url;
                try{url=PublicHttp.validate(row.getString("url"),null);}
                catch(Exception invalid){throw new IOException("منبع وب در پشتیبان معتبر نیست.",invalid);}
                String title=row.optString("title","");
                if(title.length()>240)throw new IOException("عنوان منبع بیش از حد است.");
                if(knownWebSources>=DiscoveryData.MAX_IMPORTED_SOURCES)break;
                ContentValues v=new ContentValues();
                v.put("url",url);v.put("title",title);
                v.put("added",row.optLong("added",System.currentTimeMillis()));
                if(db.insertWithOnConflict("imported_web_sources",null,v,
                        SQLiteDatabase.CONFLICT_IGNORE)!=-1)knownWebSources++;
            }
            if(buyerWords!=null){
                java.util.List<String> words=new java.util.ArrayList<>();
                for(int i=0;i<Math.min(buyerWords.length(),LeadKeywords.MAX_IMPORTED);i++)
                    words.add(buyerWords.optString(i,""));
                LeadKeywords.merge(new DiscoveryData(store),words);
            }
            if(groupWords!=null){
                java.util.List<String> words=new java.util.ArrayList<>();
                for(int i=0;i<Math.min(groupWords.length(),GroupKeywords.MAX_IMPORTED);i++)
                    words.add(groupWords.optString(i,""));
                GroupKeywords.merge(new DiscoveryData(store),words);
            }
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
        return count;
    }
}

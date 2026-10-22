package ir.electricconnect.nativefree;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/**
 * Safe offline import for one XLSX containing business phone contacts, public
 * group metadata and independent buyer/group search terms. Does not send or join.
 */
final class UnifiedExcelImport {
    private static final int MAX_FILE=8*1024*1024, MAX_EXPANDED=20*1024*1024;
    private UnifiedExcelImport(){}
    static final class Contact {
        final String name,phone,category,source;
        Contact(String n,String p,String c,String s){name=n;phone=p;category=c;source=s;}
    }
    static final class Group {
        final String name,link,chatId,source;
        Group(String n,String l,String i,String s){name=n;link=l;chatId=i;source=s;}
    }
    static final class Parsed {
        final List<Contact> contacts=new ArrayList<>();
        final List<Group> groups=new ArrayList<>();
        final List<String> buyers=new ArrayList<>();
        final List<String> groupTerms=new ArrayList<>();
        int rejected;
        int count(){return contacts.size()+groups.size()+buyers.size()+groupTerms.size();}
    }
    static final class Report {
        int contacts,groups,buyers,groupTerms,rejected;
        String describe(){return "ورودی اکسل: "+contacts+" شمارهٔ تماس، "+groups+
            " گروه، "+buyers+" عبارت مشتری‌یابی، "+groupTerms+
            " عبارت گروه‌ها اضافه شد؛ "+rejected+" ردیف نامعتبر/تکراری کنار گذاشته شد.";}
    }

    static Parsed read(InputStream input) throws Exception {
        if(input==null)throw new IOException("فایل انتخاب نشده است.");
        Map<String,byte[]> files=new LinkedHashMap<>();
        int total=0,entries=0;
        try(ZipInputStream zip=new ZipInputStream(new CappedInput(input,MAX_FILE))) {
            ZipEntry entry;byte[] buf=new byte[8192];
            while((entry=zip.getNextEntry())!=null) {
                if(++entries>300)throw new IOException("تعداد فایل‌های داخلی اکسل بیش از حد است.");
                if(entry.isDirectory())continue;
                ByteArrayOutputStream data=new ByteArrayOutputStream();
                int n;while((n=zip.read(buf))!=-1){
                    total+=n;
                    if(total>MAX_EXPANDED)throw new IOException("فایل اکسل بیش از حد بزرگ است.");
                    data.write(buf,0,n);
                }
                if(files.put(entry.getName(),data.toByteArray())!=null)
                    throw new IOException("بخش تکراری در XLSX.");
            }
        }
        if(!files.containsKey("[Content_Types].xml")||!files.containsKey("xl/workbook.xml"))
            throw new IOException("فقط فایل معتبر XLSX پشتیبانی می‌شود.");
        List<String> shared=readShared(files.get("xl/sharedStrings.xml"));
        Parsed parsed=new Parsed();
        int sheets=0;
        for(Map.Entry<String,byte[]> part:files.entrySet())
            if(part.getKey().matches("xl/worksheets/[^/]+\\.xml")) {
                if(++sheets>30)throw new IOException("تعداد شیت‌ها بیش از حد است.");
                readSheet(part.getValue(),shared,parsed);
            }
        if(sheets==0||parsed.count()==0)
            throw new IOException("هیچ مخاطب، گروه یا کلیدواژهٔ معتبری در اکسل نیست.");
        if(parsed.count()>4000)throw new IOException("حداکثر ۴۰۰۰ ردیف در یک فایل.");
        return parsed;
    }
    private static XmlPullParser xml(byte[] bytes)throws Exception {
        String s=new String(bytes,StandardCharsets.UTF_8);
        if(s.toUpperCase(Locale.ROOT).contains("<!DOCTYPE") ||
           s.toUpperCase(Locale.ROOT).contains("<!ENTITY"))
            throw new IOException("XML غیرمجاز در فایل Excel.");
        XmlPullParser parser=Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,true);
        parser.setInput(new StringReader(s));return parser;
    }
    private static List<String> readShared(byte[] raw)throws Exception {
        List<String> strings=new ArrayList<>();
        if(raw==null)return strings;
        XmlPullParser p=xml(raw);StringBuilder text=null;
        for(int e=p.next();e!=XmlPullParser.END_DOCUMENT;e=p.next()){
            if(e==XmlPullParser.START_TAG&&"si".equals(p.getName()))text=new StringBuilder();
            else if(e==XmlPullParser.START_TAG&&"t".equals(p.getName())&&text!=null)text.append(p.nextText());
            else if(e==XmlPullParser.END_TAG&&"si".equals(p.getName())) {
                strings.add(text==null?"":text.toString());text=null;
                if(strings.size()>20000)throw new IOException("بیش از حد رشتهٔ مشترک.");
            }
        }
        return strings;
    }
    private static void readSheet(byte[] bytes,List<String> shared,Parsed parsed)throws Exception {
        XmlPullParser p=xml(bytes);
        Map<String,String> row=new LinkedHashMap<>();
        Map<String,String> heads=null;
        String column="",type="";StringBuilder value=new StringBuilder();
        boolean formula=false;int rows=0;
        for(int e=p.next();e!=XmlPullParser.END_DOCUMENT;e=p.next()){
            String tag=p.getName();
            if(e==XmlPullParser.START_TAG&&"row".equals(tag)){
                row.clear();if(++rows>4000)throw new IOException("ردیف‌های شیت بیش از حد است.");
            } else if(e==XmlPullParser.START_TAG&&"c".equals(tag)){
                String ref=p.getAttributeValue(null,"r");
                column=ref==null?"A":ref.replaceAll("[0-9]","");
                type=p.getAttributeValue(null,"t");formula=false;value.setLength(0);
            } else if(e==XmlPullParser.START_TAG&&"f".equals(tag)){formula=true;}
            else if(e==XmlPullParser.START_TAG&&("v".equals(tag)||"t".equals(tag))) {
                value.append(p.nextText());
            } else if(e==XmlPullParser.END_TAG&&"c".equals(tag)&&!formula){
                String cell=value.toString();
                if("s".equals(type)){
                    int i=Integer.parseInt(cell);
                    if(i<0||i>=shared.size())throw new IOException("رشتهٔ مشترک نامعتبر است.");
                    cell=shared.get(i);
                }
                if(cell.length()>2000)cell=cell.substring(0,2000);
                if(!cell.trim().isEmpty())row.put(column,cell.trim());
            } else if(e==XmlPullParser.END_TAG&&"row".equals(tag)&&!row.isEmpty()){
                if(heads==null){
                    Map<String,String> potential=new LinkedHashMap<>();int recognized=0;
                    for(Map.Entry<String,String> c:row.entrySet()){
                        String key=SearchText.normalize(c.getValue());
                        potential.put(key,c.getKey());
                        if(isHeader(key))recognized++;
                    }
                    if(recognized>0){heads=potential;continue;}
                    heads=Collections.emptyMap();
                }
                consume(heads,row,parsed);
            }
        }
    }
    private static boolean isHeader(String s){
        return s.equals("کلیدواژه")||s.equals("keywords")||s.equals("عبارت جستجو")
            ||s.equals("نام گروه")||s.equals("لینک گروه")||s.equals("لینک عمومی گروه")
            ||s.equals("شناسه گروه")||s.equals("نام کسب و کار")||s.equals("نام شرکت")
            ||s.equals("شماره تماس")||s.equals("شماره تلفن")
            ||s.equals("نوع داده")||s.equals("حوزه")||s.equals("عنوان");
    }
    private static String field(Map<String,String> heads,Map<String,String> row,String... aliases){
        for(String alias:aliases){
            String col=heads.get(SearchText.normalize(alias));
            if(col!=null&&row.containsKey(col))return row.get(col);
        }
        return "";
    }
    private static String first(Map<String,String> row) {
        return row.isEmpty()?"":row.values().iterator().next();
    }
    private static void consume(Map<String,String> heads,Map<String,String> row,Parsed parsed){
        String type=SearchText.normalize(field(heads,row,"نوع داده","نوع"));
        String scope=SearchText.normalize(field(heads,row,"حوزه","بخش","موضوع"));
        String term=field(heads,row,"کلیدواژه","keywords","keyword","عبارت جستجو","عبارت جست و جو");
        if(heads.isEmpty())term=first(row);
        if(!term.isEmpty()){
            if(scope.contains("گروه")||type.contains("گروه")){
                if(GroupKeywords.allowed(term))parsed.groupTerms.add(term);else parsed.rejected++;
            } else {
                if(LeadKeywords.allowed(term))parsed.buyers.add(term);else parsed.rejected++;
            }
            return;
        }
        String name=field(heads,row,"نام کسب و کار","نام کسب‌وکار","نام شرکت","نام مخاطب","نام گروه","عنوان","نام");
        String phone=field(heads,row,"شماره تماس","شماره تلفن","تلفن","موبایل","شماره");
        String id=field(heads,row,"شناسه گروه","شناسه","chat id");
        String generic=field(heads,row,"شماره / شناسه / آدرس","شماره/شناسه/آدرس");
        String link=field(heads,row,"لینک عمومی گروه","لینک گروه","لینک تلگرام","آدرس گروه");
        String source=field(heads,row,"منبع","منبع / یادداشت","آدرس منبع");
        String category=field(heads,row,"دسته بندی","دسته‌بندی","دسته");
        boolean group=type.contains("گروه") || (!heads.containsKey("نوع داده")
            && (heads.containsKey("نام گروه")||heads.containsKey("لینک گروه")||heads.containsKey("شناسه گروه")));
        if(group){
            if(id.isEmpty() && !generic.isEmpty() && generic.matches("-?[0-9]{1,20}"))id=generic;
            if(link.isEmpty()&&!generic.isEmpty()&&(generic.startsWith("https://t.me/")||generic.startsWith("@")))link=generic;
            if(name.isEmpty())name=!link.isEmpty()?link:id;
            if(!name.isEmpty() && (!id.isEmpty()||!link.isEmpty()||name.length()>=3))
                parsed.groups.add(new Group(name,link,id,source));
            else parsed.rejected++;
        } else if(type.contains("مخاطب")||!phone.isEmpty()||heads.containsKey("شماره تماس")
              ||heads.containsKey("نام کسب و کار")||heads.containsKey("نام شرکت")) {
            if(phone.isEmpty())phone=generic;
            if(!name.isEmpty()&&PublicPhoneDiscovery.normalizeIranPhone(phone)!=null)
                parsed.contacts.add(new Contact(name,phone,category,source));
            else parsed.rejected++;
        } else if(!type.isEmpty()&&!type.contains("نتیجه وب")&&!type.contains("گزارش صف")) {
            parsed.rejected++;
        }
    }

    static Report apply(MainActivity.Store store,Parsed parsed)throws Exception {
        // Parse completely before any write. All SQLite changes are atomic.
        SQLiteDatabase db=store.getWritableDatabase();
        DiscoveryData discovery=new DiscoveryData(store);
        Report r=new Report();
        r.rejected=parsed.rejected;
        db.beginTransaction();
        try {
            Set<String> seen=new HashSet<>();
            try(Cursor c=db.rawQuery("SELECT phone FROM records WHERE phone<>''",null)){
                while(c.moveToNext()){
                    String p=PublicPhoneDiscovery.normalizeIranPhone(c.getString(0));
                    if(p!=null)seen.add(p);
                }
            }
            for(Contact item:parsed.contacts){
                String phone=PublicPhoneDiscovery.normalizeIranPhone(item.phone);
                if(phone==null||!seen.add(phone)){r.rejected++;continue;}
                ContentValues v=new ContentValues();
                String name=item.name.trim();
                if(name.length()>240)name=name.substring(0,240);
                String category=item.category.isEmpty()?"ورودی Excel":item.category;
                String source=item.source.isEmpty()?"ورود فایل توسط کاربر":item.source;
                v.put("title",name);v.put("phone",phone);
                v.put("category",category);v.put("source",source);
                v.put("search_text",SearchText.normalize(name+" "+phone+" "+category+" "+source));
                v.put("created",System.currentTimeMillis());v.put("retrieved",System.currentTimeMillis());
                v.put("provider","ورودی اکسل کاربر");
                if(db.insertWithOnConflict("records",null,v,SQLiteDatabase.CONFLICT_IGNORE)!=-1)r.contacts++;
                else r.rejected++;
            }
            for(Group g:parsed.groups){
                String id=SearchText.digits(g.chatId).trim();
                if(!id.isEmpty()&&!id.matches("-?[0-9]{1,20}"))id="";
                String link=g.link.trim();
                if(link.startsWith("@")&&link.matches("@[a-zA-Z0-9_]{5,32}"))
                    link="https://t.me/"+link.substring(1);
                if(!link.isEmpty() && !link.matches("https://t\\.me/[A-Za-z0-9_+/-]{2,120}"))link="";
                String key=!id.isEmpty()?"id:"+id:!link.isEmpty()?"link:"+link.toLowerCase(Locale.ROOT)
                    :"name:"+SearchText.normalize(g.name);
                if(key.length()>250||g.name.trim().isEmpty()){r.rejected++;continue;}
                ContentValues v=new ContentValues();
                v.put("lookup_key",key);v.put("title",g.name.trim());
                v.put("chat_id",id);v.put("public_link",link);
                v.put("source",g.source.trim().isEmpty()?"ورودی اکسل کاربر":g.source.trim());
                v.put("added",System.currentTimeMillis());
                if(db.insertWithOnConflict("imported_groups",null,v,SQLiteDatabase.CONFLICT_IGNORE)!=-1)r.groups++;
                else r.rejected++;
            }
            // Deduplicate within Excel; each family retains its own maximum of 50.
            r.buyers=LeadKeywords.merge(discovery,unique(parsed.buyers));
            r.groupTerms=GroupKeywords.merge(discovery,unique(parsed.groupTerms));
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        return r;
    }
    private static List<String> unique(List<String> source){
        LinkedHashMap<String,String> unique=new LinkedHashMap<>();
        for(String term:source)unique.putIfAbsent(SearchText.normalize(term),term);
        return new ArrayList<>(unique.values());
    }
    private static final class CappedInput extends FilterInputStream {
        private int remaining;
        CappedInput(InputStream input,int limit){super(input);remaining=limit;}
        @Override public int read()throws IOException{
            int n=in.read();if(n!=-1&&--remaining<0)throw new IOException("فایل ورودی بیش از ۸ مگابایت است.");return n;
        }
        @Override public int read(byte[] buf,int offset,int length)throws IOException{
            int n=in.read(buf,offset,length);if(n>0&&(remaining-=n)<0)throw new IOException("فایل ورودی بیش از ۸ مگابایت است.");return n;
        }
    }
}

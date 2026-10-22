package ir.electricconnect.nativefree;

import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Bounded XLSX reader. Reads text only; never evaluates Excel formulas or links. */
final class KeywordWorkbook {
    static final int MAX_BYTES=8*1024*1024,MAX_EXPANDED=20*1024*1024;
    static final class Result {
        final List<String> keywords;final int rejected,duplicates;
        Result(List<String> words,int rejected,int duplicates){this.keywords=words;this.rejected=rejected;this.duplicates=duplicates;}
    }
    private KeywordWorkbook(){}
    static Result read(InputStream input)throws Exception {
        Map<String,byte[]> entries=new LinkedHashMap<>();
        int size=0,count=0;
        try(ZipInputStream zip=new ZipInputStream(new LimitedInput(input,MAX_BYTES))) {
            ZipEntry entry;byte[] buffer=new byte[4096];
            while((entry=zip.getNextEntry())!=null) {
                if(++count>300)throw new IOException("فایل اکسل بیش از اندازه بزرگ است.");
                ByteArrayOutputStream body=new ByteArrayOutputStream();int n;
                while((n=zip.read(buffer))!=-1) {
                    size+=n;if(size>MAX_EXPANDED)throw new IOException("فایل اکسل بیش از اندازه بزرگ است.");
                    body.write(buffer,0,n);
                }
                if(entries.put(entry.getName(),body.toByteArray())!=null)throw new IOException("ساختار اکسل معتبر نیست.");
            }
        }
        if(!entries.containsKey("xl/workbook.xml")||!entries.containsKey("[Content_Types].xml"))
            throw new IOException("فقط فایل Excel با پسوند XLSX پشتیبانی می‌شود.");
        List<String> shared=new ArrayList<>();
        if(entries.containsKey("xl/sharedStrings.xml")) {
            XmlPullParser xml=parser(entries.get("xl/sharedStrings.xml"));StringBuilder text=null;
            for(int e=xml.next();e!=XmlPullParser.END_DOCUMENT;e=xml.next()) {
                if(e==XmlPullParser.START_TAG&&"si".equals(xml.getName()))text=new StringBuilder();
                else if(e==XmlPullParser.START_TAG&&"t".equals(xml.getName())&&text!=null)text.append(xml.nextText());
                else if(e==XmlPullParser.END_TAG&&"si".equals(xml.getName())) {shared.add(text.toString());text=null;}
            }
        }
        List<String> values=new ArrayList<>();
        for(Map.Entry<String,byte[]> entry:entries.entrySet())if(entry.getKey().matches("xl/worksheets/[^/]+\\.xml"))
            readSheet(entry.getValue(),shared,values);
        LinkedHashMap<String,String> unique=new LinkedHashMap<>();int rejected=0,duplicates=0;
        for(String raw:values) {
            String s=raw.trim();if(s.isEmpty()||header(s))continue;
            if(!LeadKeywords.allowed(s)){rejected++;continue;}
            if(unique.putIfAbsent(SearchText.normalize(s),s)!=null)duplicates++;
        }
        if(unique.isEmpty())throw new IOException("کلیدواژهٔ معتبر پیدا نشد؛ ستون اول یا ستون «کلیدواژه» باید شامل عبارت‌های مشتری‌یابی باشد.");
        if(unique.size()>LeadKeywords.MAX_IMPORTED)throw new IOException("حداکثر ۵۰ کلیدواژه در هر فهرست مجاز است.");
        return new Result(new ArrayList<>(unique.values()),rejected,duplicates);
    }
    private static boolean header(String s) {
        return Arrays.asList("keyword","keywords","search terms","کلیدواژه","کلیدواژه ها","کلید واژه","کلید واژه ها","عبارت جستجو","عبارت جست و جو").contains(SearchText.normalize(s));
    }
    private static XmlPullParser parser(byte[] body)throws Exception {
        // Reject DTDs/entities before invoking Android's XML parser.
        String raw=new String(body,java.nio.charset.StandardCharsets.UTF_8);
        if(raw.toUpperCase(Locale.ROOT).contains("<!DOCTYPE")||raw.toUpperCase(Locale.ROOT).contains("<!ENTITY"))throw new IOException("ساختار XML مجاز نیست.");
        XmlPullParser xml=Xml.newPullParser();xml.setInput(new ByteArrayInputStream(body),null);return xml;
    }
    private static void readSheet(byte[] body,List<String> shared,List<String> output)throws Exception {
        XmlPullParser xml=parser(body);
        Map<String,String> row=new LinkedHashMap<>();String col="",type="";StringBuilder value=new StringBuilder();
        boolean formula=false;String selected="A";boolean first=true;int rows=0;
        for(int e=xml.next();e!=XmlPullParser.END_DOCUMENT;e=xml.next()) {
            String name=xml.getName();
            if(e==XmlPullParser.START_TAG&&"row".equals(name)){row.clear();if(++rows>2000)throw new IOException("تعداد ردیف‌های اکسل بیش از حد است.");}
            else if(e==XmlPullParser.START_TAG&&"c".equals(name)) {
                String ref=xml.getAttributeValue(null,"r");col=ref==null?"A":ref.replaceAll("[0-9]","");
                type=xml.getAttributeValue(null,"t");value.setLength(0);formula=false;
            } else if(e==XmlPullParser.START_TAG&&"f".equals(name))formula=true;
            else if(e==XmlPullParser.START_TAG&&("v".equals(name)||"t".equals(name)))value.append(xml.nextText());
            else if(e==XmlPullParser.END_TAG&&"c".equals(name)&&!formula) {
                String text=value.toString();
                if("s".equals(type)) {int i=Integer.parseInt(text);if(i<0||i>=shared.size())throw new IOException("سلول اکسل معتبر نیست.");text=shared.get(i);}
                else if(!"inlineStr".equals(type)&&!"str".equals(type))continue;
                row.put(col,text);
            } else if(e==XmlPullParser.END_TAG&&"row".equals(name)&&!row.isEmpty()) {
                if(first) {for(Map.Entry<String,String> cell:row.entrySet())if(header(cell.getValue())){selected=cell.getKey();break;}first=false;}
                if(row.containsKey(selected))output.add(row.get(selected));
            }
        }
    }
    private static final class LimitedInput extends FilterInputStream {
        private final int limit;private int count;
        LimitedInput(InputStream input,int limit){super(input);this.limit=limit;}
        private void add(int n)throws IOException{if(n>0&&(count+=n)>limit)throw new IOException("فایل ورودی بیش از ۸ مگابایت است.");}
        @Override public int read()throws IOException{int n=in.read();if(n!=-1)add(1);return n;}
        @Override public int read(byte[] b,int off,int len)throws IOException{int n=in.read(b,off,len);add(n);return n;}
    }
}

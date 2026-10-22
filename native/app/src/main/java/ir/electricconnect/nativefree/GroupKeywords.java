package ir.electricconnect.nativefree;

import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Group discovery keywords are separate from stricter buyer lead keywords. */
final class GroupKeywords {
    static final int MAX_IMPORTED=50;
    private static final String[] DEFAULTS={
        "بازار برق","برق صنعتی","پیمانکاران برق","تجهیزات برق",
        "برق ساختمان","مهندسی برق","تاسیسات برقی","تابلو برق",
        "برق و روشنایی","مجریان برق","کابل کشی صنعتی",
        "پروژه های برق","بازار تاسیسات","پیمانکار تاسیسات",
        "صنعت روشنایی","سینی کابل","نردبان کابل"
    };
    private GroupKeywords(){}
    static boolean allowed(String value) {
        if(value==null)return false;
        String s=SearchText.normalize(value);
        return value.trim().length()>=3 && value.trim().length()<=80
            && s.matches(".*\\p{L}.*") && !s.startsWith("=")
            && !s.contains("://") && !s.startsWith("@");
    }
    static List<String> imported(DiscoveryData data) {
        List<String> result=new ArrayList<>();
        JSONArray a=data.get("group_keywords").optJSONArray("keywords");
        if(a!=null)for(int i=0;i<Math.min(MAX_IMPORTED,a.length());i++){
            String s=a.optString(i,"");
            if(allowed(s))result.add(s);
        }
        return result;
    }
    static List<String> all(DiscoveryData data) {
        LinkedHashMap<String,String> result=new LinkedHashMap<>();
        for(String s:imported(data))result.put(SearchText.normalize(s),s);
        for(String s:DEFAULTS)result.putIfAbsent(SearchText.normalize(s),s);
        return new ArrayList<>(result.values());
    }
    static int merge(DiscoveryData data,List<String> incoming) throws Exception {
        LinkedHashMap<String,String> merged=new LinkedHashMap<>();
        for(String s:imported(data))merged.put(SearchText.normalize(s),s);
        int old=merged.size();
        for(String s:incoming)if(allowed(s))merged.putIfAbsent(SearchText.normalize(s),s.trim());
        if(merged.size()>MAX_IMPORTED)throw new IllegalArgumentException("حداکثر ۵۰ عبارت گروهی مجاز است.");
        data.put("group_keywords",new JSONObject().put("keywords",new JSONArray(merged.values())));
        return merged.size()-old;
    }
}

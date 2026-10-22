package ir.electricconnect.nativefree;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** Buyer queries live in the existing SQLite database, independently of contacts. */
final class LeadKeywords {
    static final int MAX_IMPORTED=50;
    private LeadKeywords(){}
    static boolean allowed(String value) {
        String s=SearchText.normalize(value);
        return value!=null && value.trim().length()>=3 && value.trim().length()<=80
            && s.matches(".*\\p{L}.*")
            && !s.matches(".*(سینی\\s*کابل|نردبان\\s*کابل|cable\\s*tray|cable\\s*ladder|تولیدکنند|تولید\\s*کنند|فروشند|تامین\\s*کنند|تأمین\\s*کنند|manufacturer|supplier).*")
            && !s.startsWith("=") && !s.contains("://");
    }
    static List<String> imported(DiscoveryData data) {
        List<String> out=new ArrayList<>();
        JSONArray saved=data.get("buyer_keywords").optJSONArray("keywords");
        if(saved!=null)for(int i=0;i<Math.min(MAX_IMPORTED,saved.length());i++) {
            String s=saved.optString(i,"");if(allowed(s))out.add(s);
        }
        return out;
    }
    static List<String> all(DiscoveryData data) {
        LinkedHashMap<String,String> unique=new LinkedHashMap<>();
        // Imported buyer terms take priority in optional web searches.
        for(String s:imported(data))unique.put(SearchText.normalize(s),s);
        for(String s:DiscoveryCoordinator.TOPICS)unique.putIfAbsent(SearchText.normalize(s),s);
        return new ArrayList<>(unique.values());
    }
    static int merge(DiscoveryData data,List<String> values)throws Exception {
        LinkedHashMap<String,String> merged=new LinkedHashMap<>();
        for(String s:imported(data))merged.put(SearchText.normalize(s),s);
        int old=merged.size();
        for(String s:values)if(allowed(s))merged.putIfAbsent(SearchText.normalize(s),s.trim());
        if(merged.size()>MAX_IMPORTED)throw new IllegalArgumentException("حداکثر ۵۰ کلیدواژهٔ واردشده مجاز است.");
        data.put("buyer_keywords",new JSONObject().put("keywords",new JSONArray(merged.values())));
        return merged.size()-old;
    }
}

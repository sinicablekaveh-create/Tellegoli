package ir.electricconnect.nativefree;

import java.util.*;
import java.util.regex.Pattern;

/** Conservative RFC 9309-style groups, longest rules, wildcard and end matching. */
final class RobotsRules {
    private static final String AGENT="electricconnect";
    private final List<String[]> rules=new ArrayList<>();
    final List<String> sitemaps=new ArrayList<>();
    long delayMillis=1500;
    RobotsRules(String text) {
        List<String> agents=new ArrayList<>();
        List<String[]> group=new ArrayList<>();
        int best=-1;boolean directives=false;
        for(String raw:(text.replace("\ufeff","")+"\nUser-agent: end-of-file").split("\\r?\\n")) {
            String line=raw.split("#",2)[0].trim();int colon=line.indexOf(':');
            if(colon<0)continue;
            String key=line.substring(0,colon).trim().toLowerCase(Locale.ROOT),value=line.substring(colon+1).trim();
            if(key.equals("user-agent")) {
                if(directives) {
                    int score=score(agents);
                    if(score>=best && score>=0) {if(score>best)rules.clear();rules.addAll(group);best=score;}
                    agents.clear();group.clear();directives=false;
                }
                agents.add(value.toLowerCase(Locale.ROOT));
            } else if(key.equals("allow")||key.equals("disallow")) {
                directives=true;if(!value.isEmpty())group.add(new String[]{key,value});
            } else if(key.equals("sitemap"))sitemaps.add(value);
            else if(key.equals("crawl-delay") && score(agents)>=0) {
                try {delayMillis=Math.max(delayMillis,(long)(Double.parseDouble(value)*1000));}catch(NumberFormatException ignored){}
            }
        }
        delayMillis=Math.max(1500,delayMillis);
    }
    private static int score(List<String> agents) {
        int score=-1;for(String agent:agents) {
            if(agent.equals("*"))score=Math.max(score,0);
            else if(!agent.isEmpty()&&AGENT.contains(agent))score=Math.max(score,agent.length());
        }return score;
    }
    boolean allows(String path) {
        boolean allowed=true;int longest=-1;
        for(String[] rule:rules) {
            String value=rule[1];
            try {value=java.net.URLDecoder.decode(value,"UTF-8");}catch(Exception ignored){}
            boolean end=value.endsWith("$");
            if(end)value=value.substring(0,value.length()-1);
            StringBuilder regex=new StringBuilder("^");
            for(String chunk:value.split("\\*",-1)) {if(regex.length()>1)regex.append(".*");regex.append(Pattern.quote(chunk));}
            if(end)regex.append('$');
            int weight=value.replace("*","").length();
            if(Pattern.compile(regex.toString()).matcher(path).find() && (weight>longest || weight==longest && rule[0].equals("allow"))) {
                longest=weight;allowed=rule[0].equals("allow");
            }
        }
        return allowed;
    }
}

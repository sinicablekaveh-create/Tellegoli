package ir.electricconnect.nativefree;

import java.util.regex.Pattern;

/** Conservative relevance gate: evidence of project execution, never product-only suppliers. */
final class BuyerRelevance {
    private static final Pattern BUYER=Pattern.compile(
        "پیمانکار.{0,45}(برق|الکتریک|پروژه|صنعت|ساختمان)|"
        +"(مجری|اجرای|اجرا|نصب).{0,45}(کابل کشی|برق|تاسیسات الکتریک|تاسیسات برق)|"
        +"(برق|تاسیسات الکتریک|تاسیسات برق).{0,45}(پیمانکار|اجرا|پروژه)|"
        +"\\bepc\\b|electrical contractor|electrical installation|industrial wiring");
    private static final Pattern SUPPLIER=Pattern.compile(
        "(تولید|فروش|تامین|تأمین|ساخت).{0,35}(سینی کابل|نردبان کابل)|"
        +"(سینی کابل|نردبان کابل).{0,35}(تولید|فروش|تامین|تأمین)|"
        +"cable (tray|ladder).{0,30}(manufactur|supplier)|"
        +"(manufactur|supplier).{0,30}cable (tray|ladder)");
    private BuyerRelevance(){}
    static boolean accepts(String title,String body) {
        String heading=SearchText.normalize(title==null?"":title);
        String text=SearchText.normalize(body==null?"":body);
        if(SUPPLIER.matcher(heading).find())return false;
        // A product site can mention contractors as its customers; that is not buyer evidence.
        if(SUPPLIER.matcher(text).find()&&!BUYER.matcher(heading).find())return false;
        return BUYER.matcher(heading+" "+text).find();
    }
}

package ir.electricconnect.nativefree;

/** Public groups are leads to review, not proof of a buyer or permission to post. */
final class GroupRelevance {
    private GroupRelevance(){}
    static boolean accepts(String title) {
        String s=SearchText.normalize(title==null?"":title);
        return s.matches(".*(برق|الکتریک|تاسیسات|تأسیسات|کابل|تابلو|روشنایی|پیمانکار|electri|cable|lighting|contractor).*");
    }
}

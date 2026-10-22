package ir.electricconnect.nativefree;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Genuine Office Open XML spreadsheet writer, no network, no third-party
 * service and no storage permission. Everything is exported only when the
 * user selects a destination via Android's Create Document picker.
 *
 * Cells are typed as inline strings: phones retain leading zeroes, long
 * Telegram IDs are not rounded, and arbitrary values never become formulas.
 */
final class ExcelWorkbook {
    static final String MIME =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private ExcelWorkbook(){}

    static void write(OutputStream target,String sheetName,List<String[]> rows) throws IOException {
        if(target==null || rows==null)throw new IllegalArgumentException("No export target or rows");
        try(ZipOutputStream zip=new ZipOutputStream(target,StandardCharsets.UTF_8)){
            entry(zip,"[Content_Types].xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"+
                "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"+
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"+
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"+
                "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"+
                "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"+
                "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"+
                "</Types>");
            entry(zip,"_rels/.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"+
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"+
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"+
                "</Relationships>");
            entry(zip,"xl/workbook.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"+
                "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""+
                " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"+
                "<bookViews><workbookView/></bookViews><sheets><sheet name=\""+
                xml(sheetName==null||sheetName.isEmpty()?"خروجی":sheetName.substring(0,Math.min(sheetName.length(),31)))+
                "\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            entry(zip,"xl/_rels/workbook.xml.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"+
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"+
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"+
                "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"+
                "</Relationships>");
            entry(zip,"xl/styles.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"+
                "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"+
                "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font>"+
                "<font><b/><sz val=\"11\"/><color rgb=\"FFFFFFFF\"/><name val=\"Calibri\"/></font></fonts>"+
                "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill>"+
                "<fill><patternFill patternType=\"gray125\"/></fill>"+
                "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF00656E\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>"+
                "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"+
                "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"+
                "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"+
                "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFill=\"1\" applyFont=\"1\"/></cellXfs>"+
                "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>");
            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            emit(zip,"<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"+
                    "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"+
                    "<sheetViews><sheetView workbookViewId=\"0\" rightToLeft=\"1\">"+
                    "<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>"+
                    "</sheetView></sheetViews><sheetFormatPr defaultRowHeight=\"18\"/>");
            int cols=rows.isEmpty()?1:rows.get(0).length;
            emit(zip,"<cols>");
            for(int col=1;col<=cols && col<=24;col++)
                emit(zip,"<col min=\""+col+"\" max=\""+col+"\" width=\""+
                    (col==1?"39":col==2?"23":col==3?"26":"46")+
                    "\" customWidth=\"1\"/>");
            emit(zip,"</cols><sheetData>");
            int rowIndex=1;
            for(String[] row:rows){
                if(rowIndex>1048576)break;
                emit(zip,"<row r=\""+rowIndex+"\">");
                if(row!=null)for(int c=0;c<row.length && c<24;c++){
                    String value=row[c]==null?"":row[c];
                    if(value.length()>32000)value=value.substring(0,32000);
                    String letter=column(c+1);
                    emit(zip,"<c r=\""+letter+rowIndex+"\""+
                        (rowIndex==1?" s=\"1\"":"")+
                        " t=\"inlineStr\"><is><t xml:space=\"preserve\">"+
                        xml(value)+"</t></is></c>");
                }
                emit(zip,"</row>");
                rowIndex++;
            }
            emit(zip,"</sheetData><autoFilter ref=\"A1:"+column(Math.min(24,Math.max(1,cols)))+
                Math.max(1,rowIndex-1)+"\"/></worksheet>");
            zip.closeEntry();
        }
    }
    private static String column(int idx){
        StringBuilder b=new StringBuilder();
        while(idx>0){idx--;b.insert(0,(char)('A'+idx%26));idx/=26;}
        return b.toString();
    }
    private static String xml(String raw){
        StringBuilder b=new StringBuilder(raw.length()+20);
        for(int i=0;i<raw.length();i++){
            char c=raw.charAt(i);
            if(c=='&')b.append("&amp;");
            else if(c=='<')b.append("&lt;");
            else if(c=='>')b.append("&gt;");
            else if(c=='"')b.append("&quot;");
            else if(c=='\'')b.append("&apos;");
            else if(c==9||c==10||c==13 || c>=32 && c!=0xFFFE && c!=0xFFFF){
                if(Character.isHighSurrogate(c)){
                    if(i+1<raw.length() && Character.isLowSurrogate(raw.charAt(i+1)))
                        b.append(c).append(raw.charAt(++i));
                } else if(!Character.isLowSurrogate(c))b.append(c);
            }
        }
        return b.toString();
    }
    private static void entry(ZipOutputStream zip,String path,String xml) throws IOException {
        zip.putNextEntry(new ZipEntry(path));emit(zip,xml);zip.closeEntry();
    }
    private static void emit(OutputStream out,String raw) throws IOException {
        out.write(raw.getBytes(StandardCharsets.UTF_8));
    }
}

import java.io.File;
import java.io.IOException;

import org.omg.CORBA.PUBLIC_MEMBER;


public class HtmlContent {
	public static String getContent(String filename) {
		String content = "" ;
		try {
			File filein = new File(filename) ;
			if(filein == null) return content ;
			org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(filein, "GBK","http://example.com/") ;
			String title = doc.title() ;
			content += title ;
			if(doc.body() != null) content += doc.body().text() ;
		} catch (Exception e) {
			// TODO: handle exception
			System.out.println("open file fail " + filename) ;
		}
		//System.out.println(content);
		return content ;
	}
}

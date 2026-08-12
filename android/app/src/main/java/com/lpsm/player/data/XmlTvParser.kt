package com.lpsm.player.data
import android.util.Xml
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.*
data class Program(val channel:String,val start:Date,val stop:Date,val title:String)
object XmlTvParser { fun current(xml:String,now:Date=Date()):Map<String,String>{val out=mutableMapOf<String,String>();val p=Xml.newPullParser();p.setInput(StringReader(xml));var channel="";var start:Date?=null;var stop:Date?=null;var title="";while(p.eventType!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT){if(p.eventType==org.xmlpull.v1.XmlPullParser.START_TAG){when(p.name){"programme"->{channel=p.getAttributeValue(null,"channel")?:"";start=date(p.getAttributeValue(null,"start"));stop=date(p.getAttributeValue(null,"stop"))};"title"->title=p.nextText()}}else if(p.eventType==org.xmlpull.v1.XmlPullParser.END_TAG&&p.name=="programme"){if(start!=null&&stop!=null&&!now.before(start)&&now.before(stop))out[channel]=title;channel="";title=""};p.next()};return out}
 private fun date(v:String?):Date?{if(v==null)return null;return try{SimpleDateFormat("yyyyMMddHHmmss Z",Locale.US).parse(v.take(20))}catch(_:Exception){null}}
}

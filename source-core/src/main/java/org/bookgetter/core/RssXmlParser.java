package org.bookgetter.core;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.SAXParserFactory;
import org.bookgetter.core.RssModels.*;
import org.jsoup.Jsoup;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.ext.DefaultHandler2;

/** Standard RSS item branch. DTDs and entity expansion are forbidden. */
final class RssXmlParser {
    private RssXmlParser() {}
    static List<Article> parse(String xml, RssSource source, String sort) {
        try {
            // Android's Expat does not implement Xerces' disallow-doctype feature.
            // Reject the XML declaration token before parsing on every platform; entity
            // declarations cannot be introduced outside a DTD or by character references.
            if (xml.contains("<!DOCTYPE")) throw new SAXException("DTDs are forbidden");
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(false);
            feature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
            feature(factory, "http://xml.org/sax/features/external-general-entities", false);
            feature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            Handler handler = new Handler(source, sort);
            var reader = factory.newSAXParser().getXMLReader();
            reader.setEntityResolver((publicId, systemId) -> { throw new SAXException("External entities are forbidden"); });
            reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler);
            reader.setContentHandler(handler);
            reader.setErrorHandler(handler);
            reader.parse(new InputSource(new StringReader(xml)));
            return handler.articles;
        } catch (Exception error) {
            throw new Models.SourceFailure(Models.Stage.SEARCH, source.id(), "ruleArticles", "RSS_XML_ERROR", "Invalid or unsafe RSS XML", error);
        }
    }

    private static void feature(SAXParserFactory factory, String name, boolean value) throws javax.xml.parsers.ParserConfigurationException {
        try { factory.setFeature(name, value); }
        catch (org.xml.sax.SAXNotRecognizedException | org.xml.sax.SAXNotSupportedException unavailable) {
            // The token guard and rejecting resolver remain active on non-Xerces parsers.
        }
    }

    private static final class Handler extends DefaultHandler2 {
        private final RssSource source;
        private final String sort;
        private final List<Article> articles = new ArrayList<>();
        private final StringBuilder value = new StringBuilder();
        private boolean inside;
        private String field;
        private int depth;
        private String title, link, date, description, content, image;
        Handler(RssSource source, String sort) { this.source = source; this.sort = sort; }
        @Override public void startDTD(String name, String publicId, String systemId) throws SAXException {
            throw new SAXException("DTDs are forbidden");
        }
        @Override public void startElement(String uri, String local, String name, Attributes attrs) {
            if (name.equalsIgnoreCase("item")) {
                inside = true; title = ""; link = ""; date = description = content = image = null;
                return;
            }
            if (!inside) return;
            if (field != null) { depth++; return; }
            String key = name.toLowerCase(java.util.Locale.ROOT);
            switch (key) {
                case "media:thumbnail" -> image = attrs.getValue("url");
                case "enclosure" -> {
                    String type = attrs.getValue("type");
                    if (type != null && type.contains("image/")) image = attrs.getValue("url");
                }
                case "title", "link", "pubdate", "time", "description", "content:encoded" -> {
                    field = key; depth = 1; value.setLength(0);
                }
                default -> { }
            }
        }
        @Override public void characters(char[] data, int start, int length) {
            if (field != null) value.append(data, start, length);
        }
        @Override public void endElement(String uri, String local, String name) {
            if (field != null) {
                if (--depth != 0) return;
                String text = value.toString().trim();
                switch (field) {
                    case "title" -> title = text;
                    case "link" -> link = text;
                    case "pubdate", "time" -> date = text;
                    case "description" -> description = text;
                    case "content:encoded" -> content = text;
                    default -> { }
                }
                if (image == null && (field.equals("description") || field.equals("content:encoded"))) {
                    var first = Jsoup.parse(text).selectFirst("img[src]");
                    if (first != null) image = first.attr("src");
                }
                field = null;
            } else if (inside && name.equalsIgnoreCase("item")) {
                if (articles.size() >= 10_000) throw new IllegalArgumentException("Too many RSS articles");
                articles.add(new Article(source.id(), sort, title, link, date, description, content, image,
                        source.kind(), Map.of()));
                inside = false;
            }
        }
    }
}

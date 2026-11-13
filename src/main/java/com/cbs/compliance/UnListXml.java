package com.cbs.compliance;

import java.io.StringReader;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

/**
 * Converts the UN Security Council consolidated list (XML) into the screening list's row form
 * (entry_ref, entity_type, name, aliases, birth_date, country, program), which then goes through the same
 * validation as a CSV upload.
 *
 * <ul>
 *   <li>entry_ref is the UN reference number (for example {@code QDi.001}), or DATAID when it is missing.</li>
 *   <li>The name joins FIRST_NAME to FOURTH_NAME; aliases come from INDIVIDUAL_ALIAS / ENTITY_ALIAS, good-quality
 *       aliases first. Names beyond the screening limit of 20 are dropped, low-quality aliases first.</li>
 *   <li>Only an exact date of birth is kept. Year-only or approximate dates are left out, because a date of
 *       birth is used to rule a match out and an approximate one must not do that.</li>
 *   <li>Country is left empty: the list gives nationality as a country name, not a code.</li>
 *   <li>program is the UN list type (for example "Al-Qaida").</li>
 * </ul>
 *
 * <p>The parser refuses DOCTYPE declarations and external entities.
 */
final class UnListXml {
    private UnListXml() { }

    static final int MAX_NAMES = 20;

    static List<List<String>> records(String xml) {
        Element root;
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null);
            String body = xml.startsWith("﻿") ? xml.substring(1) : xml;
            root = builder.parse(new InputSource(new StringReader(body))).getDocumentElement();
        } catch (Exception invalid) {
            throw ScreeningService.error(422, "SCREENING_LIST_INVALID", "The file is not a readable UN consolidated list XML document");
        }
        if (!"CONSOLIDATED_LIST".equals(root.getTagName())) {
            throw ScreeningService.error(422, "SCREENING_LIST_INVALID", "The root element must be CONSOLIDATED_LIST");
        }
        var records = new ArrayList<List<String>>();
        records.add(List.of("entry_ref", "entity_type", "name", "aliases", "birth_date", "country", "program"));
        for (Element individual : descendants(root, "INDIVIDUAL")) records.add(record(individual, "INDIVIDUAL", "INDIVIDUAL_ALIAS"));
        for (Element entity : descendants(root, "ENTITY")) records.add(record(entity, "ENTITY", "ENTITY_ALIAS"));
        return records;
    }

    private static List<String> record(Element item, String type, String aliasTag) {
        String ref = text(item, "REFERENCE_NUMBER");
        if (ref.isEmpty()) ref = text(item, "DATAID");
        var parts = new ArrayList<String>();
        for (String tag : List.of("FIRST_NAME", "SECOND_NAME", "THIRD_NAME", "FOURTH_NAME")) {
            String part = text(item, tag);
            if (!part.isEmpty()) parts.add(part);
        }
        String name = String.join(" ", parts);
        var good = new ArrayList<String>();
        var low = new ArrayList<String>();
        for (Element alias : children(item, aliasTag)) {
            String value = text(alias, "ALIAS_NAME").replace(';', ',');
            if (value.isEmpty()) continue;
            ("Low".equalsIgnoreCase(text(alias, "QUALITY")) ? low : good).add(value);
        }
        var aliases = new ArrayList<String>(good);
        aliases.addAll(low);
        // The screening row allows 20 names in total, including the primary name.
        if (aliases.size() > MAX_NAMES - 1) aliases = new ArrayList<>(aliases.subList(0, MAX_NAMES - 1));
        String born = "";
        if (type.equals("INDIVIDUAL")) {
            for (Element date : children(item, "INDIVIDUAL_DATE_OF_BIRTH")) {
                String kind = text(date, "TYPE_OF_DATE"), exact = text(date, "DATE");
                if (("EXACT".equalsIgnoreCase(kind) || kind.isEmpty()) && exact.matches("\\d{4}-\\d{2}-\\d{2}.*")) { born = exact.substring(0, 10); break; }
            }
        }
        return List.of(ref, type, name.replace(';', ','), String.join(";", aliases), born, "", text(item, "UN_LIST_TYPE"));
    }

    private static List<Element> descendants(Element root, String tag) {
        var out = new ArrayList<Element>();
        var nodes = root.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) out.add((Element) nodes.item(i));
        return out;
    }

    private static List<Element> children(Element parent, String tag) {
        var out = new ArrayList<Element>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element e && e.getTagName().equals(tag)) out.add(e);
        return out;
    }

    private static String text(Element parent, String tag) {
        var found = children(parent, tag);
        return found.isEmpty() ? "" : found.getFirst().getTextContent().strip().replaceAll("\\s+", " ");
    }
}

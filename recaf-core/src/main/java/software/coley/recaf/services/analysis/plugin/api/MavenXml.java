package software.coley.recaf.services.analysis.plugin.api;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * XML helpers for reading repository files <i>(POMs and maven-metadata)</i>.
 * <p>
 * The files come over the network, so the parser is locked down: no DTDs, no entities, no external resources.
 */
final class MavenXml {
	private MavenXml() {}

	/**
	 * @param content
	 * 		Raw XML.
	 *
	 * @return Root element of the document.
	 *
	 * @throws IOException
	 * 		When the content is not valid XML, or uses features that are not allowed.
	 */
	@Nonnull
	static Element parse(@Nonnull byte[] content) throws IOException {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setXIncludeAware(false);
			factory.setExpandEntityReferences(false);
			factory.setNamespaceAware(false);
			Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(content));
			return document.getDocumentElement();
		} catch (Exception ex) {
			throw new IOException("Invalid XML: " + ex.getMessage(), ex);
		}
	}

	/**
	 * @param parent
	 * 		Element to look in.
	 * @param name
	 * 		Name of the child element.
	 *
	 * @return First direct child element with the name, or {@code null}.
	 */
	@Nullable
	static Element child(@Nonnull Element parent, @Nonnull String name) {
		for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
			if (node instanceof Element element && element.getTagName().equals(name))
				return element;
		return null;
	}

	/**
	 * @param parent
	 * 		Element to look in.
	 * @param name
	 * 		Name of the child elements.
	 *
	 * @return All direct child elements with the name.
	 */
	@Nonnull
	static List<Element> children(@Nonnull Element parent, @Nonnull String name) {
		List<Element> list = new ArrayList<>();
		for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
			if (node instanceof Element element && element.getTagName().equals(name))
				list.add(element);
		return list;
	}

	/**
	 * @param parent
	 * 		Element to look in.
	 * @param name
	 * 		Name of the child element.
	 *
	 * @return Trimmed text of the child element, or {@code null} if absent or blank.
	 */
	@Nullable
	static String text(@Nonnull Element parent, @Nonnull String name) {
		Element child = child(parent, name);
		if (child == null)
			return null;
		String text = child.getTextContent().trim();
		return text.isEmpty() ? null : text;
	}
}

package software.coley.recaf.services.attach;

import jakarta.annotation.Nonnull;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

import javax.management.*;

/**
 * Extension of {@link MBeanInfo} providing access to the associated {@link ObjectName}.
 *
 * @author Matt Coley
 */
public class NamedMBeanInfo extends MBeanInfo {
	private final ObjectName objectName;

	/**
	 * @param objectName
	 * 		Associated name to the bean info.
	 * @param info
	 * 		Wrapped bean info.
	 */
	public NamedMBeanInfo(@Nonnull ObjectName objectName, @Nonnull MBeanInfo info) {
		this(objectName,
				info.getClassName(),
				info.getDescription(),
				info.getAttributes(),
				info.getConstructors(),
				info.getOperations(),
				info.getNotifications(),
				info.getDescriptor());
	}

	private NamedMBeanInfo(@Nonnull ObjectName objectName,
						   String className,
						   String description,
						   MBeanAttributeInfo[] attributes,
						   MBeanConstructorInfo[] constructors,
						   MBeanOperationInfo[] operations,
						   MBeanNotificationInfo[] notifications,
						   Descriptor descriptor) throws IllegalArgumentException {
		super(className, description, attributes, constructors, operations, notifications, descriptor);
		this.objectName = objectName;
	}

	/**
	 * @return Key to use in {@link MBeanServerConnection} operations.
	 */
	@Nonnull
	public ObjectName getObjectName() {
		return objectName;
	}

	/**
	 * @param connection
	 * 		JMX connection.
	 * @param attribute
	 * 		Attribute to get value of.
	 *
	 * @return Value of attribute.
	 *
	 * @throws Exception
	 * 		See: {@link MBeanServerConnection#getAttribute(ObjectName, String)}.
	 */
	public Object getAttributeValue(@Nonnull JmxBeanServerConnection connection,
									@Nonnull MBeanAttributeInfo attribute) throws Exception {
		return getAttributeValue(connection, attribute.getName());
	}

	/**
	 * @param connection
	 * 		JMX connection.
	 * @param attributeName
	 * 		Name of attribute to get value of.
	 *
	 * @return Value of attribute.
	 *
	 * @throws Exception
	 * 		See: {@link MBeanServerConnection#getAttribute(ObjectName, String)}.
	 */
	public Object getAttributeValue(@Nonnull JmxBeanServerConnection connection,
									@Nonnull String attributeName) throws Exception {
		return connection.getConnection().getAttribute(getObjectName(), attributeName);
	}
	/**
	 * Reads every attribute of the bean. Attributes that cannot be read are shown as {@code ?} rather than
	 * failing the whole read, since some depend on optional VM features.
	 *
	 * @param connection
	 * 		Connection to the bean's server.
	 *
	 * @return Attribute names, sorted, mapped to their values as text. Arrays are shown as their elements.
	 */
	@Nonnull
	public SortedMap<String, String> readAttributeValues(@Nonnull JmxBeanServerConnection connection) {
		SortedMap<String, String> values = new TreeMap<>();
		for (MBeanAttributeInfo attribute : getAttributes()) {
			String text;
			try {
				Object value = getAttributeValue(connection, attribute);
				text = value != null && value.getClass().isArray() ? arrayToString(value) : Objects.toString(value);
			} catch (Exception ex) {
				text = "?";
			}
			values.put(attribute.getName(), text);
		}
		return values;
	}

	@Nonnull
	private static String arrayToString(@Nonnull Object array) {
		int length = Array.getLength(array);
		Object[] boxed = new Object[length];
		for (int i = 0; i < length; i++)
			boxed[i] = Array.get(array, i);
		return Arrays.toString(boxed);
	}
}

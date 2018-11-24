package ru.runa.wfe.web.framework.core;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.val;
import org.apache.commons.lang.StringUtils;

/**
 * Add custom type mappings by overriding {@link #convertValuesToType(String[], Type)} and {@link #convertValueToType(String, Type)} methods.
 * <p>
 * Parameter names:
 * <ul>
 *     <li>Can be compound, like this: aaa1.bbb_2[3].ccc[string-key].ddd
 *     <li>Names starting with _ are ignored. This is done for "?_=YYYYMMDDhhmmss" and for other stuff like debug params.
 *     <li>Each name part must correspond to Java class field name; first letter must be lowercase [a-z].
 *     <li>Indexes can be anything that does not contain ']'.
 *         Indexes can NOT be empty, because otherwise "a[].b" and "a[].c" may map to "a[0].b" and "a[1].c".
 *     <li>Multi-valued parameters are supported (if parameter maps to ArrayList field).
 * </ul>
 * <p>
 * Target class (and in case of compound parameter names, classes of its fields, recursively):
 * <ul>
 *     <li>Must have default constructor.
 *     <li>Fields must be public; they are accessed directly (setters are not used) and I don't want to , .
 *     <li>For param name parts with indexes, only ArrayList and HashMap fields are supported.
 *         For ArrayHist, only non-negative integer indexes are supported. Gaps are allowed, missing elements are filled with nulls.
 *         For HashMap, key must be non-generic type supported by {@link #convertValueToType(String, Type)}.
 *     <li>For multi-valued params, only ArrayList is supported.
 *     <li>Top-level target class cannot be ArrayList or Map itself, since parameter name part cannot have index without field name.
 * </ul>
 *
 * NOTE: I could use commons-beanutils, but it looks far too complex for my needs; and as of version 1.8.3 currently
 * used by wfe, all custom mappers are registered globally, so I'm afraid to break something by configuring it to my needs.
 * I also could use Jackson, but that requires creating intermediate Map which is inefficient.
 * Anyway, current implementation is simple enough to bother searching for (surely more complex) third-party alternatives.
 *
 * @see ServletConfiguration
 * @author Dmitry Grigoriev (dimgel)
 */
public class RequestParamsParser {

    /**
     * @throws Exception Means error 400.
     */
    final <C> C parse(Map<String, String> pathParams, Map<String, String[]> requestParams, Class<C> targetClass) throws Exception {
        C o = targetClass.newInstance();
        for (val kv : pathParams.entrySet()) {
            setFieldValue(o, kv.getKey(), new String[] { kv.getValue() });
        }
        for (val kv : requestParams.entrySet()) {
            String name = kv.getKey();
            if (name.charAt(0) != '_') {
                setFieldValue(o, name, kv.getValue());
            }
        }
        return o;
    }

    private void setFieldValue(Object o, String name, String[] values) {
        try {
            String[] nameParts = StringUtils.split(name, '.');
            processNamePart(o, nameParts, 0, values);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to assign parameter \"" + name + "\", targetClass " + o.getClass(), e);
        }
    }

    // Examples of matched strings: "a", "ab_22c", "abc[33], abc[some-string]".
    private static final Pattern regexNamePart = Pattern.compile("([a-z]\\w*)(?:\\[([^]]+)])?");

    private void processNamePart(Object o, String[] nameParts, int namePartIdx, String[] values) throws Exception {
        String namePart = nameParts[namePartIdx];
        Matcher m = regexNamePart.matcher(namePart);
        if (!m.matches()) {
            throw fail(nameParts, namePartIdx, " is malformed");
        }
        String fieldName = m.group(1);
        String indexStringOrNull = m.group(2);

        Field f;
        try {
            f = o.getClass().getField(fieldName);  //.getDeclaringClass().getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            throw fail(nameParts, namePartIdx, " is not a field of class " + o.getClass());
        }
        Class<?> fc = f.getType();
        Object fv = f.get(o);

        boolean isArrayList;
        Object indexOrNull;
        Type valueType;
        if (indexStringOrNull == null) {
            isArrayList = false;  // unused
            indexOrNull = null;
            valueType = f.getGenericType();
        } else {
            if (fv == null) {
                fv = fc.newInstance();
                f.set(o, fv);
            }
            isArrayList = fc.isAssignableFrom(ArrayList.class);
            if (isArrayList) {
                int idx = Integer.parseInt(indexStringOrNull);
                indexOrNull = idx;
                valueType = ((ParameterizedType) f.getGenericType()).getActualTypeArguments()[0];
                val list = (ArrayList)fv;
                list.ensureCapacity(idx + 1);
                // "n >= 0" instead of "n > 0" to add null for list[idx] too, to make code below shorter.
                for (int n = idx - list.size();  n >= 0;  n--) {
                    //noinspection unchecked
                    list.add(null);
                }
            } else if (fc.isAssignableFrom(HashMap.class)) {
                val ft = (ParameterizedType) f.getGenericType();
                val keyType = (Class) ft.getActualTypeArguments()[0];
                indexOrNull = convertValueToType(indexStringOrNull, keyType);
                valueType = ft.getActualTypeArguments()[1];
            } else {
                throw fail(nameParts, namePartIdx, " has index, but corresponding field is " + fv.getClass() + " (neither ArrayList nor HashMap)");
            }
        }

        boolean isLastNamePart = namePartIdx == nameParts.length - 1;
        if (isLastNamePart) {

            Object value = convertValuesToType(values, valueType);
            if (indexOrNull == null) {
                f.set(o, value);
            } else if (isArrayList) {
                // If duplicate parameter name (possible because input is TWO maps), overwrite previously written value.
                //noinspection unchecked
                ((ArrayList) fv).set((Integer) indexOrNull, value);
            } else {
                // If duplicate parameter name (possible because input is TWO maps), overwrite previously written value.
                //noinspection unchecked
                ((HashMap) fv).put(indexOrNull, value);
            }

        } else {

            if (fv == null) {
                fv = fc.newInstance();
                f.set(o, fv);
            }
            Object subObject;
            if (indexOrNull == null) {
                subObject = fv;
            } else {
                if (isArrayList) {
                    val list = (ArrayList) fv;
                    int idx = (Integer) indexOrNull;
                    subObject = list.get(idx);
                    if (subObject == null) {
                        subObject = convertTypeToClass(valueType).newInstance();
                        //noinspection unchecked
                        list.set(idx, subObject);
                    }
                } else {
                    val map = (HashMap) fv;
                    subObject = map.get(indexOrNull);
                    if (subObject == null) {
                        subObject = convertTypeToClass(valueType).newInstance();
                        //noinspection unchecked
                        map.put(indexOrNull, subObject);
                    }
                }
            }
            processNamePart(subObject, nameParts, namePartIdx + 1, values);
        }
    }

    @SuppressWarnings("WeakerAccess")
    protected Object convertValuesToType(String[] values, Type t) throws Exception {
        if (convertTypeToClass(t).isAssignableFrom(ArrayList.class)) {
            val list = new ArrayList(values.length);
            val vt = ((ParameterizedType) t).getActualTypeArguments()[0];
            for (String v : values) {
                //noinspection unchecked
                list.add(convertValueToType(v, vt));
            }
            return list;
        } else {
            if (values.length != 1) {
                throw new Exception("Got values.length " + values.length + " != 1 for type" + t);
            }
            return convertValueToType(values[0], t);
        }
    }

    @SuppressWarnings("WeakerAccess")
    protected Object convertValueToType(String value, Type t) throws Exception {
        if (value == null) {
            return null;
        } else if (t == boolean.class || t == Boolean.class) {
            switch (value) {
                case "1":
                    return Boolean.TRUE;
                case "0":
                    return Boolean.FALSE;
                default:
                    throw new Exception("Invalid boolean value");
            }
        } else if (t == byte.class || t == Byte.class) {
            return Byte.parseByte(value);
        } else if (t == char.class || t == Character.class) {
            if (value.length() == 1) {
                return value.charAt(0);
            } else {
                throw new Exception("Invalid char value");
            }
        } else if (t == short.class || t == Short.class) {
            return Short.parseShort(value);
        } else if (t == int.class || t == Integer.class) {
            return Integer.parseInt(value);
        } else if (t == long.class || t == Long.class) {
            return Long.parseLong(value);
        } else if (t == float.class || t == Float.class) {
            return Float.parseFloat(value);
        } else if (t == double.class || t == Double.class) {
            return Double.parseDouble(value);
        } else if (t == String.class) {
            return value;
        } else {
            // TODO UUID?
            // TODO Date: yyyy-MM-dd, yyyy-MM-dd HH:mm:ss?
            throw new Exception("Unsupported value type " + t);
        }
    }

    private static Class<?> convertTypeToClass(Type t) throws Exception {
        if (t instanceof Class) {
            return (Class) t;
        } else if (t instanceof ParameterizedType) {
            return (Class) ((ParameterizedType) t).getRawType();
        } else {
            throw new Exception("Unexpected type subclass " + t);
        }
    }

    private static Exception fail(String[] nameParts, int namePartIdx, String msg) {
        val sb = new StringBuilder("Parameter name part \"");
        sb.append(nameParts[0]);
        for (int i = 1;  i <= namePartIdx;  i++) {
            sb.append(".").append(nameParts[i]);
        }
        sb.append("\" ").append(msg);
        return new Exception(sb.toString());
    }
}
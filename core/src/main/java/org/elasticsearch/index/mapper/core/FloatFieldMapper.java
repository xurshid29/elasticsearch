/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.mapper.core;

import com.carrotsearch.hppc.FloatArrayList;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType.NumericType;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.Terms;
import org.apache.lucene.search.NumericRangeQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefBuilder;
import org.apache.lucene.util.NumericUtils;
import org.elasticsearch.action.fieldstats.FieldStats;
import org.elasticsearch.common.Explicit;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.Numbers;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.Fuzziness;
import org.elasticsearch.common.util.ByteUtils;
import org.elasticsearch.common.util.CollectionUtils;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.analysis.NamedAnalyzer;
import org.elasticsearch.index.analysis.NumericFloatAnalyzer;
import org.elasticsearch.index.fielddata.FieldDataType;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.Mapper;
import org.elasticsearch.index.mapper.MapperParsingException;
import org.elasticsearch.index.mapper.MergeMappingException;
import org.elasticsearch.index.mapper.MergeResult;
import org.elasticsearch.index.mapper.ParseContext;
import org.elasticsearch.index.query.QueryParseContext;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.apache.lucene.util.NumericUtils.floatToSortableInt;
import static org.elasticsearch.common.xcontent.support.XContentMapValues.nodeFloatValue;
import static org.elasticsearch.index.mapper.MapperBuilders.floatField;
import static org.elasticsearch.index.mapper.core.TypeParsers.parseNumberField;

/**
 *
 */
public class FloatFieldMapper extends NumberFieldMapper {

    public static final String CONTENT_TYPE = "float";

    public static class Defaults extends NumberFieldMapper.Defaults {
        public static final MappedFieldType FIELD_TYPE = new FloatFieldType();

        static {
            FIELD_TYPE.freeze();
        }
    }

    public static class Builder extends NumberFieldMapper.Builder<Builder, FloatFieldMapper> {

        public Builder(String name) {
            super(name, Defaults.FIELD_TYPE, Defaults.PRECISION_STEP_32_BIT);
            builder = this;
        }

        @Override
        public FloatFieldMapper build(BuilderContext context) {
            setupFieldType(context);
            FloatFieldMapper fieldMapper = new FloatFieldMapper(fieldType, docValues, ignoreMalformed(context), coerce(context),
                    fieldDataSettings, context.indexSettings(), multiFieldsBuilder.build(this, context), copyTo);
            fieldMapper.includeInAll(includeInAll);
            return fieldMapper;
        }

        @Override
        protected NamedAnalyzer makeNumberAnalyzer(int precisionStep) {
            return NumericFloatAnalyzer.buildNamedAnalyzer(precisionStep);
        }

        @Override
        protected int maxPrecisionStep() {
            return 32;
        }
    }

    public static class TypeParser implements Mapper.TypeParser {
        @Override
        public Mapper.Builder parse(String name, Map<String, Object> node, ParserContext parserContext) throws MapperParsingException {
            FloatFieldMapper.Builder builder = floatField(name);
            parseNumberField(builder, name, node, parserContext);
            for (Iterator<Map.Entry<String, Object>> iterator = node.entrySet().iterator(); iterator.hasNext();) {
                Map.Entry<String, Object> entry = iterator.next();
                String propName = Strings.toUnderscoreCase(entry.getKey());
                Object propNode = entry.getValue();
                if (propName.equals("null_value")) {
                    if (propNode == null) {
                        throw new MapperParsingException("Property [null_value] cannot be null.");
                    }
                    builder.nullValue(nodeFloatValue(propNode));
                    iterator.remove();
                }
            }
            return builder;
        }
    }

    static final class FloatFieldType extends NumberFieldType {

        public FloatFieldType() {
            super(NumericType.FLOAT);
        }

        protected FloatFieldType(FloatFieldType ref) {
            super(ref);
        }

        @Override
        public NumberFieldType clone() {
            return new FloatFieldType(this);
        }

        @Override
        public String typeName() {
            return CONTENT_TYPE;
        }

        @Override
        public Float nullValue() {
            return (Float)super.nullValue();
        }

        @Override
        public Float value(Object value) {
            if (value == null) {
                return null;
            }
            if (value instanceof Number) {
                return ((Number) value).floatValue();
            }
            if (value instanceof BytesRef) {
                return Numbers.bytesToFloat((BytesRef) value);
            }
            return Float.parseFloat(value.toString());
        }

        @Override
        public BytesRef indexedValueForSearch(Object value) {
            int intValue = NumericUtils.floatToSortableInt(parseValue(value));
            BytesRefBuilder bytesRef = new BytesRefBuilder();
            NumericUtils.intToPrefixCoded(intValue, 0, bytesRef);   // 0 because of exact match
            return bytesRef.get();
        }

        @Override
        public Query rangeQuery(Object lowerTerm, Object upperTerm, boolean includeLower, boolean includeUpper, @Nullable QueryParseContext context) {
            return NumericRangeQuery.newFloatRange(names().indexName(), numericPrecisionStep(),
                lowerTerm == null ? null : parseValue(lowerTerm),
                upperTerm == null ? null : parseValue(upperTerm),
                includeLower, includeUpper);
        }

        @Override
        public Query fuzzyQuery(String value, Fuzziness fuzziness, int prefixLength, int maxExpansions, boolean transpositions) {
            float iValue = Float.parseFloat(value);
            final float iSim = fuzziness.asFloat();
            return NumericRangeQuery.newFloatRange(names().indexName(), numericPrecisionStep(),
                iValue - iSim,
                iValue + iSim,
                true, true);
        }

        @Override
        public FieldStats stats(Terms terms, int maxDoc) throws IOException {
            float minValue = NumericUtils.sortableIntToFloat(NumericUtils.getMinInt(terms));
            float maxValue = NumericUtils.sortableIntToFloat(NumericUtils.getMaxInt(terms));
            return new FieldStats.Float(
                maxDoc, terms.getDocCount(), terms.getSumDocFreq(), terms.getSumTotalTermFreq(), minValue, maxValue
            );
        }
    }

    protected FloatFieldMapper(MappedFieldType fieldType, Boolean docValues,
                               Explicit<Boolean> ignoreMalformed, Explicit<Boolean> coerce,
                               @Nullable Settings fieldDataSettings, Settings indexSettings, MultiFields multiFields, CopyTo copyTo) {
        super(fieldType, docValues, ignoreMalformed, coerce, fieldDataSettings, indexSettings, multiFields, copyTo);
    }

    @Override
    public FloatFieldType fieldType() {
        return (FloatFieldType) super.fieldType();
    }

    @Override
    public MappedFieldType defaultFieldType() {
        return Defaults.FIELD_TYPE;
    }

    @Override
    public FieldDataType defaultFieldDataType() {
        return new FieldDataType("float");
    }

    private static float parseValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).floatValue();
        }
        if (value instanceof BytesRef) {
            return Float.parseFloat(((BytesRef) value).utf8ToString());
        }
        return Float.parseFloat(value.toString());
    }

    @Override
    protected boolean customBoost() {
        return true;
    }

    @Override
    protected void innerParseCreateField(ParseContext context, List<Field> fields) throws IOException {
        float value;
        float boost = fieldType().boost();
        if (context.externalValueSet()) {
            Object externalValue = context.externalValue();
            if (externalValue == null) {
                if (fieldType().nullValue() == null) {
                    return;
                }
                value = fieldType().nullValue();
            } else if (externalValue instanceof String) {
                String sExternalValue = (String) externalValue;
                if (sExternalValue.length() == 0) {
                    if (fieldType().nullValue() == null) {
                        return;
                    }
                    value = fieldType().nullValue();
                } else {
                    value = Float.parseFloat(sExternalValue);
                }
            } else {
                value = ((Number) externalValue).floatValue();
            }
            if (context.includeInAll(includeInAll, this)) {
                context.allEntries().addText(fieldType().names().fullName(), Float.toString(value), boost);
            }
        } else {
            XContentParser parser = context.parser();
            if (parser.currentToken() == XContentParser.Token.VALUE_NULL ||
                    (parser.currentToken() == XContentParser.Token.VALUE_STRING && parser.textLength() == 0)) {
                if (fieldType().nullValue() == null) {
                    return;
                }
                value = fieldType().nullValue();
                if (fieldType().nullValueAsString() != null && (context.includeInAll(includeInAll, this))) {
                    context.allEntries().addText(fieldType().names().fullName(), fieldType().nullValueAsString(), boost);
                }
            } else if (parser.currentToken() == XContentParser.Token.START_OBJECT) {
                XContentParser.Token token;
                String currentFieldName = null;
                Float objValue = fieldType().nullValue();
                while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                    if (token == XContentParser.Token.FIELD_NAME) {
                        currentFieldName = parser.currentName();
                    } else {
                        if ("value".equals(currentFieldName) || "_value".equals(currentFieldName)) {
                            if (parser.currentToken() != XContentParser.Token.VALUE_NULL) {
                                objValue = parser.floatValue(coerce.value());
                            }
                        } else if ("boost".equals(currentFieldName) || "_boost".equals(currentFieldName)) {
                            boost = parser.floatValue();
                        } else {
                            throw new IllegalArgumentException("unknown property [" + currentFieldName + "]");
                        }
                    }
                }
                if (objValue == null) {
                    // no value
                    return;
                }
                value = objValue;
            } else {
                value = parser.floatValue(coerce.value());
                if (context.includeInAll(includeInAll, this)) {
                    context.allEntries().addText(fieldType().names().fullName(), parser.text(), boost);
                }
            }
        }

        if (fieldType().indexOptions() != IndexOptions.NONE || fieldType().stored()) {
            CustomFloatNumericField field = new CustomFloatNumericField(this, value, fieldType());
            field.setBoost(boost);
            fields.add(field);
        }
        if (fieldType().hasDocValues()) {
            if (useSortedNumericDocValues) {
                addDocValue(context, fields, floatToSortableInt(value));
            } else {
                CustomFloatNumericDocValuesField field = (CustomFloatNumericDocValuesField) context.doc().getByKey(fieldType().names().indexName());
                if (field != null) {
                    field.add(value);
                } else {
                    field = new CustomFloatNumericDocValuesField(fieldType().names().indexName(), value);
                    context.doc().addWithKey(fieldType().names().indexName(), field);
                }
            }
        }
    }

    @Override
    protected String contentType() {
        return CONTENT_TYPE;
    }

    @Override
    protected void doXContentBody(XContentBuilder builder, boolean includeDefaults, Params params) throws IOException {
        super.doXContentBody(builder, includeDefaults, params);

        if (includeDefaults || fieldType().numericPrecisionStep() != Defaults.PRECISION_STEP_32_BIT) {
            builder.field("precision_step", fieldType().numericPrecisionStep());
        }
        if (includeDefaults || fieldType().nullValue() != null) {
            builder.field("null_value", fieldType().nullValue());
        }
        if (includeInAll != null) {
            builder.field("include_in_all", includeInAll);
        } else if (includeDefaults) {
            builder.field("include_in_all", false);
        }

    }

    public static class CustomFloatNumericField extends CustomNumericField {

        private final float number;

        private final NumberFieldMapper mapper;

        public CustomFloatNumericField(NumberFieldMapper mapper, float number, NumberFieldType fieldType) {
            super(mapper, number, fieldType);
            this.mapper = mapper;
            this.number = number;
        }

        @Override
        public TokenStream tokenStream(Analyzer analyzer, TokenStream previous) throws IOException {
            if (fieldType().indexOptions() != IndexOptions.NONE) {
                return mapper.popCachedStream().setFloatValue(number);
            }
            return null;
        }

        @Override
        public String numericAsString() {
            return Float.toString(number);
        }
    }

    public static class CustomFloatNumericDocValuesField extends CustomNumericDocValuesField {

        private final FloatArrayList values;

        public CustomFloatNumericDocValuesField(String  name, float value) {
            super(name);
            values = new FloatArrayList();
            add(value);
        }

        public void add(float value) {
            values.add(value);
        }

        @Override
        public BytesRef binaryValue() {
            CollectionUtils.sortAndDedup(values);

            final byte[] bytes = new byte[values.size() * 4];
            for (int i = 0; i < values.size(); ++i) {
                ByteUtils.writeFloatLE(values.get(i), bytes, i * 4);
            }
            return new BytesRef(bytes);
        }

    }
}

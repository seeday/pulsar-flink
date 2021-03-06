/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.connectors.pulsar.internal;

import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.api.schema.GenericRecord;
import org.apache.pulsar.client.api.schema.GenericSchema;
import org.apache.pulsar.client.impl.schema.BooleanSchema;
import org.apache.pulsar.client.impl.schema.ByteSchema;
import org.apache.pulsar.client.impl.schema.BytesSchema;
import org.apache.pulsar.client.impl.schema.DateSchema;
import org.apache.pulsar.client.impl.schema.DoubleSchema;
import org.apache.pulsar.client.impl.schema.FloatSchema;
import org.apache.pulsar.client.impl.schema.IntSchema;
import org.apache.pulsar.client.impl.schema.LocalDateSchema;
import org.apache.pulsar.client.impl.schema.LocalDateTimeSchema;
import org.apache.pulsar.client.impl.schema.LocalTimeSchema;
import org.apache.pulsar.client.impl.schema.LongSchema;
import org.apache.pulsar.client.impl.schema.ShortSchema;
import org.apache.pulsar.client.impl.schema.TimeSchema;
import org.apache.pulsar.client.impl.schema.TimestampSchema;
import org.apache.pulsar.client.impl.schema.generic.GenericSchemaImpl;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.common.protocol.schema.PostSchemaPayload;
import org.apache.pulsar.common.schema.SchemaInfo;
import org.apache.pulsar.common.schema.SchemaType;
import org.apache.pulsar.shade.org.apache.avro.Schema;

import java.nio.charset.StandardCharsets;

import static org.apache.pulsar.shade.com.google.common.base.Preconditions.checkNotNull;

/**
 * Various utilities to working with Pulsar Schema and Flink type system.
 */
public class SchemaUtils {

    public static void uploadPulsarSchema(PulsarAdmin admin, String topic, SchemaInfo schemaInfo) {
        checkNotNull(schemaInfo);

        SchemaInfo existingSchema;
        try {
            existingSchema = admin.schemas().getSchemaInfo(TopicName.get(topic).toString());
        } catch (PulsarAdminException pae) {
            if (pae.getStatusCode() == 404) {
                existingSchema = null;
            } else {
                throw new RuntimeException(
                        String.format("Failed to get schema information for %s", TopicName.get(topic).toString()), pae);
            }
        } catch (Throwable e) {
            throw new RuntimeException(
                    String.format("Failed to get schema information for %s", TopicName.get(topic).toString()), e);
        }

        if (existingSchema == null) {
            PostSchemaPayload pl = new PostSchemaPayload();
            pl.setType(schemaInfo.getType().name());
            pl.setSchema(new String(schemaInfo.getSchema(), StandardCharsets.UTF_8));
            pl.setProperties(schemaInfo.getProperties());

            try {
                admin.schemas().createSchema(TopicName.get(topic).toString(), pl);
            } catch (PulsarAdminException pae) {
                if (pae.getStatusCode() == 404) {
                    throw new RuntimeException(
                            String.format("Create schema for %s get 404", TopicName.get(topic).toString()), pae);
                } else {
                    throw new RuntimeException(
                            String.format("Failed to create schema information for %s", TopicName.get(topic).toString()), pae);
                }
            } catch (Throwable e) {
                throw new RuntimeException(
                        String.format("Failed to create schema information for %s", TopicName.get(topic).toString()), e);
            }
        } else if (!existingSchema.equals(schemaInfo) && !compatibleSchema(existingSchema, schemaInfo)) {
            throw new RuntimeException("Writing to a topic which have incompatible schema");
        }
    }

    public static boolean compatibleSchema(SchemaInfo s1, SchemaInfo s2) {
        if (s1.getType() == SchemaType.NONE && s2.getType() == SchemaType.BYTES) {
            return true;
        } else {
            return s1.getType() == SchemaType.BYTES && s2.getType() == SchemaType.NONE;
        }
    }

    public static org.apache.pulsar.client.api.Schema<?> getPulsarSchema(SchemaInfo schemaInfo) {
        switch (schemaInfo.getType()) {
            case BOOLEAN:
                return BooleanSchema.of();
            case INT8:
                return ByteSchema.of();
            case INT16:
                return ShortSchema.of();
            case INT32:
                return IntSchema.of();
            case INT64:
                return LongSchema.of();
            case STRING:
                return org.apache.pulsar.client.api.Schema.STRING;
            case FLOAT:
                return FloatSchema.of();
            case DOUBLE:
                return DoubleSchema.of();
            case BYTES:
            case NONE:
                return BytesSchema.of();
            case DATE:
                return DateSchema.of();
            case TIME:
                return TimeSchema.of();
            case TIMESTAMP:
                return TimestampSchema.of();
                // 需要注意，这是新版本才有的类型
            case LOCAL_DATE:
                return LocalDateSchema.of();
            case LOCAL_TIME:
                return LocalTimeSchema.of();
            case LOCAL_DATE_TIME:
                return LocalDateTimeSchema.of();
            case AVRO:
            case JSON:
                return GenericSchemaImpl.of(schemaInfo);
            default:
                throw new IllegalArgumentException("Retrieve schema instance from schema info for type " +
                        schemaInfo.getType() + " is not supported yet");
        }
    }

    static GenericSchema<GenericRecord> avroSchema2PulsarSchema(Schema avroSchema) {
        byte[] schemaBytes = avroSchema.toString().getBytes(StandardCharsets.UTF_8);
        SchemaInfo si = new SchemaInfo();
        si.setName("Avro");
        si.setSchema(schemaBytes);
        si.setType(SchemaType.AVRO);
        return org.apache.pulsar.client.api.Schema.generic(si);
    }

    public static SchemaInfo emptySchemaInfo() {
        return SchemaInfo.builder()
                .name("empty")
                .type(SchemaType.NONE)
                .schema(new byte[0])
                .build();
    }

    private static final Schema NULL_SCHEMA = Schema.create(Schema.Type.NULL);

    private static int[] minBytesForPrecision = new int[39];

    static {
        for (int i = 0; i < minBytesForPrecision.length; i++) {
            minBytesForPrecision[i] = computeMinBytesForPrecision(i);
        }
    }

    private static int computeMinBytesForPrecision(int precision) {
        int numBytes = 1;
        while (Math.pow(2.0, 8 * numBytes - 1) < Math.pow(10.0, precision)) {
            numBytes += 1;
        }
        return numBytes;
    }
}

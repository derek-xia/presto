/*
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
package com.facebook.presto.orc.metadata;

import com.facebook.presto.orc.metadata.ColumnEncoding.ColumnEncodingKind;
import com.facebook.presto.orc.metadata.OrcType.OrcTypeKind;
import com.facebook.presto.orc.metadata.Stream.StreamKind;
import com.facebook.presto.orc.metadata.statistics.ColumnStatistics;
import com.facebook.presto.orc.proto.DwrfProto;
import com.facebook.presto.orc.proto.DwrfProto.RowIndexEntry;
import com.facebook.presto.orc.proto.DwrfProto.Type;
import com.facebook.presto.orc.proto.DwrfProto.Type.Builder;
import com.facebook.presto.orc.proto.DwrfProto.UserMetadataItem;
import com.facebook.presto.orc.protobuf.ByteString;
import com.facebook.presto.orc.protobuf.MessageLite;
import com.google.common.io.CountingOutputStream;
import io.airlift.slice.Slice;
import io.airlift.slice.SliceOutput;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map.Entry;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.lang.Math.toIntExact;

public class DwrfMetadataWriter
        implements MetadataWriter
{
    private static final int DWRF_WRITER_VERSION = 1;

    @Override
    public int writePostscript(SliceOutput output, int footerLength, int metadataLength, CompressionKind compression, int compressionBlockSize)
            throws IOException
    {
        DwrfProto.PostScript postScriptProtobuf = DwrfProto.PostScript.newBuilder()
                .setFooterLength(footerLength)
                .setWriterVersion(DWRF_WRITER_VERSION)
                .setCompression(toCompression(compression))
                .setCompressionBlockSize(compressionBlockSize)
                .build();

        return writeProtobufObject(output, postScriptProtobuf);
    }

    @Override
    public int writeMetadata(SliceOutput output, Metadata metadata)
            throws IOException
    {
        return 0;
    }

    @Override
    public int writeFooter(SliceOutput output, Footer footer)
            throws IOException
    {
        DwrfProto.Footer footerProtobuf = DwrfProto.Footer.newBuilder()
                .setNumberOfRows(footer.getNumberOfRows())
                .setRowIndexStride(footer.getRowsInRowGroup())
                .addAllStripes(footer.getStripes().stream()
                        .map(DwrfMetadataWriter::toStripeInformation)
                        .collect(toImmutableList()))
                .addAllTypes(footer.getTypes().stream()
                        .map(DwrfMetadataWriter::toType)
                        .collect(toImmutableList()))
                .addAllStatistics(footer.getFileStats().stream()
                        .map(DwrfMetadataWriter::toColumnStatistics)
                        .collect(toImmutableList()))
                .addAllMetadata(footer.getUserMetadata().entrySet().stream()
                        .map(DwrfMetadataWriter::toUserMetadata)
                        .collect(toImmutableList()))
                .build();

        return writeProtobufObject(output, footerProtobuf);
    }

    private static DwrfProto.StripeInformation toStripeInformation(StripeInformation stripe)
    {
        return DwrfProto.StripeInformation.newBuilder()
                .setNumberOfRows(stripe.getNumberOfRows())
                .setOffset(stripe.getOffset())
                .setIndexLength(stripe.getIndexLength())
                .setDataLength(stripe.getDataLength())
                .setFooterLength(stripe.getFooterLength())
                .build();
    }

    private static Type toType(OrcType type)
    {
        Builder builder = Type.newBuilder()
                .setKind(toTypeKind(type.getOrcTypeKind()))
                .addAllSubtypes(type.getFieldTypeIndexes())
                .addAllFieldNames(type.getFieldNames());

        return builder.build();
    }

    private static Type.Kind toTypeKind(OrcTypeKind orcTypeKind)
    {
        switch (orcTypeKind) {
            case BOOLEAN:
                return Type.Kind.BOOLEAN;
            case BYTE:
                return Type.Kind.BYTE;
            case SHORT:
                return Type.Kind.SHORT;
            case INT:
                return Type.Kind.INT;
            case LONG:
                return Type.Kind.LONG;
            case FLOAT:
                return Type.Kind.FLOAT;
            case DOUBLE:
                return Type.Kind.DOUBLE;
            case STRING:
            case VARCHAR:
                return Type.Kind.STRING;
            case BINARY:
                return Type.Kind.BINARY;
            case TIMESTAMP:
                return Type.Kind.TIMESTAMP;
            case LIST:
                return Type.Kind.LIST;
            case MAP:
                return Type.Kind.MAP;
            case STRUCT:
                return Type.Kind.STRUCT;
            case UNION:
                return Type.Kind.UNION;
        }
        throw new IllegalArgumentException("Unsupported type: " + orcTypeKind);
    }

    private static DwrfProto.ColumnStatistics toColumnStatistics(ColumnStatistics columnStatistics)
    {
        DwrfProto.ColumnStatistics.Builder builder = DwrfProto.ColumnStatistics.newBuilder();

        if (columnStatistics.hasNumberOfValues()) {
            builder.setNumberOfValues(columnStatistics.getNumberOfValues());
        }

        if (columnStatistics.getBooleanStatistics() != null) {
            builder.setBucketStatistics(DwrfProto.BucketStatistics.newBuilder()
                    .addCount(columnStatistics.getBooleanStatistics().getTrueValueCount())
                    .build());
        }

        if (columnStatistics.getIntegerStatistics() != null) {
            builder.setIntStatistics(DwrfProto.IntegerStatistics.newBuilder()
                    .setMinimum(columnStatistics.getIntegerStatistics().getMin())
                    .setMaximum(columnStatistics.getIntegerStatistics().getMax())
                    .build());
        }

        if (columnStatistics.getDoubleStatistics() != null) {
            builder.setDoubleStatistics(DwrfProto.DoubleStatistics.newBuilder()
                    .setMinimum(columnStatistics.getDoubleStatistics().getMin())
                    .setMaximum(columnStatistics.getDoubleStatistics().getMax())
                    .build());
        }

        if (columnStatistics.getStringStatistics() != null) {
            builder.setStringStatistics(DwrfProto.StringStatistics.newBuilder()
                    .setMinimum(columnStatistics.getStringStatistics().getMin().toStringUtf8())
                    .setMaximum(columnStatistics.getStringStatistics().getMax().toStringUtf8())
                    .build());
        }

        return builder.build();
    }

    private static UserMetadataItem toUserMetadata(Entry<String, Slice> entry)
    {
        return UserMetadataItem.newBuilder()
                .setName(entry.getKey())
                .setValue(ByteString.copyFrom(entry.getValue().getBytes()))
                .build();
    }

    @Override
    public int writeStripeFooter(SliceOutput output, StripeFooter footer)
            throws IOException
    {
        DwrfProto.StripeFooter footerProtobuf = DwrfProto.StripeFooter.newBuilder()
                .addAllStreams(footer.getStreams().stream()
                        .map(DwrfMetadataWriter::toStream)
                        .collect(toImmutableList()))
                .addAllColumns(footer.getColumnEncodings().stream()
                        .map(DwrfMetadataWriter::toColumnEncoding)
                        .collect(toImmutableList()))
                .build();

        return writeProtobufObject(output, footerProtobuf);
    }

    private static DwrfProto.Stream toStream(Stream stream)
    {
        return DwrfProto.Stream.newBuilder()
                .setColumn(stream.getColumn())
                .setKind(toStreamKind(stream.getStreamKind()))
                .setLength(stream.getLength())
                .setUseVInts(stream.isUseVInts())
                .build();
    }

    private static DwrfProto.Stream.Kind toStreamKind(StreamKind streamKind)
    {
        switch (streamKind) {
            case PRESENT:
                return DwrfProto.Stream.Kind.PRESENT;
            case DATA:
                return DwrfProto.Stream.Kind.DATA;
            case SECONDARY:
                return DwrfProto.Stream.Kind.NANO_DATA;
            case LENGTH:
                return DwrfProto.Stream.Kind.LENGTH;
            case DICTIONARY_DATA:
                return DwrfProto.Stream.Kind.DICTIONARY_DATA;
            case DICTIONARY_COUNT:
                return DwrfProto.Stream.Kind.DICTIONARY_COUNT;
            case ROW_INDEX:
                return DwrfProto.Stream.Kind.ROW_INDEX;
        }
        throw new IllegalArgumentException("Unsupported stream kind: " + streamKind);
    }

    private static DwrfProto.ColumnEncoding toColumnEncoding(ColumnEncoding columnEncodings)
    {
        return DwrfProto.ColumnEncoding.newBuilder()
                .setKind(toColumnEncoding(columnEncodings.getColumnEncodingKind()))
                .setDictionarySize(columnEncodings.getDictionarySize())
                .build();
    }

    private static DwrfProto.ColumnEncoding.Kind toColumnEncoding(ColumnEncodingKind columnEncodingKind)
    {
        switch (columnEncodingKind) {
            case DIRECT:
                return DwrfProto.ColumnEncoding.Kind.DIRECT;
            case DICTIONARY:
                return DwrfProto.ColumnEncoding.Kind.DICTIONARY;
        }
        throw new IllegalArgumentException("Unsupported column encoding kind: " + columnEncodingKind);
    }

    @Override
    public int writeRowIndexes(SliceOutput output, List<RowGroupIndex> rowGroupIndexes)
            throws IOException
    {
        DwrfProto.RowIndex rowIndexProtobuf = DwrfProto.RowIndex.newBuilder()
                .addAllEntry(rowGroupIndexes.stream()
                        .map(DwrfMetadataWriter::toRowGroupIndex)
                        .collect(toImmutableList()))
                .build();
        return writeProtobufObject(output, rowIndexProtobuf);
    }

    private static RowIndexEntry toRowGroupIndex(RowGroupIndex rowGroupIndex)
    {
        return RowIndexEntry.newBuilder()
                .addAllPositions(rowGroupIndex.getPositions().stream()
                        .map(Integer::longValue)
                        .collect(toImmutableList()))
                .setStatistics(toColumnStatistics(rowGroupIndex.getColumnStatistics()))
                .build();
    }

    private static DwrfProto.CompressionKind toCompression(CompressionKind compressionKind)
    {
        switch (compressionKind) {
            case NONE:
                return DwrfProto.CompressionKind.NONE;
            case ZLIB:
                return DwrfProto.CompressionKind.ZLIB;
            case SNAPPY:
                return DwrfProto.CompressionKind.SNAPPY;
        }
        throw new IllegalArgumentException("Unsupported compression kind: " + compressionKind);
    }

    private static int writeProtobufObject(OutputStream output, MessageLite object)
            throws IOException
    {
        CountingOutputStream countingOutput = new CountingOutputStream(output);
        object.writeTo(countingOutput);
        return toIntExact(countingOutput.getCount());
    }
}

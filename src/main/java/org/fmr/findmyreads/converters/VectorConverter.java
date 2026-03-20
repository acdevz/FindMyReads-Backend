package org.fmr.findmyreads.converters;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Converts between Java double[] and PostgreSQL vector literal string.
 *
 * DB column type : vector(768)
 * Java field type: double[]
 *
 * PG wire format : [0.1,0.2,0.3,...] — square brackets, comma-separated
 *
 * Applied via @Convert(converter = VectorConverter.class) on any double[] field
 * that maps to a vector(N) column.
 */
@Converter
public class VectorConverter implements AttributeConverter<double[], String> {

    @Override
    public String convertToDatabaseColumn(double[] vector) {
        if (vector == null) return null;

        return new StringBuilder()
                .append("[")
                .append(Arrays.stream(vector)
                        .mapToObj(Double::toString)
                        .collect(Collectors.joining(",")))
                .append("]")
                .toString();
    }

    @Override
    public double[] convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return null;

        // strip surrounding [ ] then split on comma
        String stripped = dbData.strip();
        if (stripped.startsWith("[")) {
            stripped = stripped.substring(1, stripped.length() - 1);
        }

        String[] parts = stripped.split(",");
        double[] result = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Double.parseDouble(parts[i].strip());
        }
        return result;
    }
}

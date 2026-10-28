package com.bankos.service;

import com.bankos.model.Payment;
import com.bankos.model.Settlement;
import com.bankos.model.SettlementStatus;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Parses payments.csv and settlements.csv with Apache Commons CSV. Every parsed row keeps its
 * source file and row number so findings can cite it. Malformed rows are quarantined, not guessed.
 */
@Service
public class CsvImportService {

    public static final List<String> PAYMENT_COLUMNS =
            List.of("payment_id", "reference", "supplier", "amount", "currency", "value_date", "invoice_number");
    public static final List<String> SETTLEMENT_COLUMNS =
            List.of("settlement_id", "payment_id", "reference", "amount", "currency", "status", "settled_at");

    private static final Set<String> REQUIRED_PAYMENT_COLUMNS = Set.of("payment_id", "reference", "amount", "currency");
    private static final Set<String> REQUIRED_SETTLEMENT_COLUMNS =
            Set.of("settlement_id", "payment_id", "reference", "amount", "currency", "status");

    private static final Pattern AMOUNT = Pattern.compile("-?\\d+(\\.\\d{1,2})?");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreHeaderCase(true)
            .setIgnoreEmptyLines(true)
            .setTrim(true)
            .get();

    public record ParseResult<T>(List<T> rows, List<String> quarantined) {}

    /** Checks that the file is a CSV with the required header columns; throws a 400 otherwise. */
    public void validatePaymentsHeader(byte[] content, String fileName) {
        validateHeader(content, fileName, REQUIRED_PAYMENT_COLUMNS);
    }

    public void validateSettlementsHeader(byte[] content, String fileName) {
        validateHeader(content, fileName, REQUIRED_SETTLEMENT_COLUMNS);
    }

    private void validateHeader(byte[] content, String fileName, Set<String> required) {
        try (CSVParser parser = FORMAT.parse(new StringReader(new String(content, StandardCharsets.UTF_8)))) {
            Set<String> present = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            present.addAll(parser.getHeaderNames());
            List<String> missing = required.stream().filter(c -> !present.contains(c)).sorted().toList();
            if (!missing.isEmpty()) {
                throw ApiException.badRequest(fileName + " is missing required columns", missing);
            }
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            throw ApiException.badRequest(fileName + " is not a readable CSV file", List.of(e.getMessage()));
        }
    }

    public ParseResult<Payment> parsePayments(Path file, String batchId, String sourceName) throws IOException {
        return parse(file, sourceName, record -> new Payment(
                batchId,
                required(record, "payment_id"),
                optional(record, "reference"),
                optional(record, "supplier"),
                amount(record),
                currency(record),
                date(record, "value_date"),
                optional(record, "invoice_number"),
                (int) record.getRecordNumber(),
                sourceName));
    }

    public ParseResult<Settlement> parseSettlements(Path file, String batchId, String sourceName) throws IOException {
        return parse(file, sourceName, record -> new Settlement(
                batchId,
                required(record, "settlement_id"),
                optional(record, "payment_id"),
                optional(record, "reference"),
                amount(record),
                currency(record),
                status(record),
                date(record, "settled_at"),
                (int) record.getRecordNumber(),
                sourceName));
    }

    private <T> ParseResult<T> parse(Path file, String sourceName, Function<CSVRecord, T> mapper) throws IOException {
        List<T> rows = new ArrayList<>();
        List<String> quarantined = new ArrayList<>();
        try (InputStream in = Files.newInputStream(file);
             Reader reader = new java.io.InputStreamReader(in, StandardCharsets.UTF_8);
             CSVParser parser = FORMAT.parse(reader)) {
            for (CSVRecord record : parser) {
                try {
                    rows.add(mapper.apply(record));
                } catch (IllegalArgumentException e) {
                    quarantined.add(sourceName + " row " + record.getRecordNumber() + ": " + e.getMessage());
                }
            }
        }
        return new ParseResult<>(rows, quarantined);
    }

    private static String value(CSVRecord record, String column) {
        if (!record.isMapped(column) || !record.isSet(column)) {
            return null;
        }
        String value = record.get(column);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String required(CSVRecord record, String column) {
        String value = value(record, column);
        if (value == null) {
            throw new IllegalArgumentException(column + " is required");
        }
        return value;
    }

    private static String optional(CSVRecord record, String column) {
        return value(record, column);
    }

    private static BigDecimal amount(CSVRecord record) {
        String raw = required(record, "amount");
        if (!AMOUNT.matcher(raw).matches()) {
            throw new IllegalArgumentException("amount '" + raw + "' is not a decimal with at most 2 fraction digits");
        }
        return new BigDecimal(raw).setScale(2, RoundingMode.UNNECESSARY);
    }

    private static String currency(CSVRecord record) {
        String raw = required(record, "currency").toUpperCase(Locale.ROOT);
        if (!CURRENCY.matcher(raw).matches()) {
            throw new IllegalArgumentException("currency '" + raw + "' is not an ISO 4217 code");
        }
        return raw;
    }

    private static LocalDate date(CSVRecord record, String column) {
        String raw = value(record, column);
        if (raw == null) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(column + " '" + raw + "' is not YYYY-MM-DD");
        }
    }

    private static SettlementStatus status(CSVRecord record) {
        String raw = required(record, "status").toUpperCase(Locale.ROOT);
        try {
            return SettlementStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("status '" + raw + "' must be SETTLED, PENDING or REVERSED");
        }
    }
}

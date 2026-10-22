/*
 * © 2024-2026 SAP SE or an SAP affiliate company and cds-feature-attachments contributors.
 */
package com.sap.cds.feature.attachments.handler.applicationservice.helper;

import com.sap.cds.CdsData;
import com.sap.cds.feature.attachments.generated.cds4j.sap.attachments.Attachments;
import com.sap.cds.feature.attachments.handler.common.ApplicationHandlerHelper;
import com.sap.cds.ql.CQL;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.cqn.CqnPredicate;
import com.sap.cds.ql.cqn.CqnSelectListItem;
import com.sap.cds.reflect.CdsAssociationType;
import com.sap.cds.reflect.CdsEntity;
import com.sap.cds.reflect.CdsModel;
import com.sap.cds.services.persistence.PersistenceService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The class {@link FilenameDeduplicator} appends a numeric suffix to attachment {@code fileName}
 * fields when an attachment with the same name already exists under the same parent entity. For
 * example {@code report.pdf} becomes {@code report-1.pdf}, {@code report-2.pdf}, etc.
 *
 * <p>Only activated when the {@code cds.attachments.deduplicateFileNames} property is {@code true}.
 */
public final class FilenameDeduplicator {

  private static final Logger logger = LoggerFactory.getLogger(FilenameDeduplicator.class);

  /**
   * Renames conflicting {@code fileName} values in {@code data} by appending a numeric suffix.
   * Supports both composition-based and inline attachment type fields.
   *
   * @param model the CDS model
   * @param entity the target entity of the CREATE event
   * @param data the incoming request data (mutated in place)
   * @param persistence the persistence service used to query existing filenames
   */
  public static void deduplicate(
      CdsModel model, CdsEntity entity, List<CdsData> data, PersistenceService persistence) {

    if (ApplicationHandlerHelper.isDirectMediaEntity(entity)) {
      deduplicateCompositionEntity(entity, data, persistence);
      return;
    }

    deduplicateCompositionChildren(entity, data, persistence);
    deduplicateInlineAttachments(entity, data, persistence);
  }

  // ---------------------------------------------------------------------------
  // Composition-based attachments
  // ---------------------------------------------------------------------------

  private static void deduplicateCompositionEntity(
      CdsEntity attachmentEntity, List<CdsData> data, PersistenceService persistence) {

    List<String> parentKeyNames = getParentKeyNames(attachmentEntity);
    if (parentKeyNames.isEmpty()) {
      return;
    }

    Map<String, Map<String, Object>> tupleToKeys = new HashMap<>();
    Map<String, Set<String>> tupleToIncoming = new HashMap<>();

    for (CdsData item : data) {
      String fileName = (String) item.get(Attachments.FILE_NAME);
      if (fileName == null) {
        continue;
      }
      Map<String, Object> tuple = extractTuple(item, parentKeyNames);
      if (tuple.isEmpty()) {
        continue;
      }
      String tupleKey = tupleKey(tuple, parentKeyNames);
      tupleToKeys.putIfAbsent(tupleKey, tuple);
      tupleToIncoming.computeIfAbsent(tupleKey, k -> new HashSet<>()).add(fileName);
    }

    if (tupleToKeys.isEmpty()) {
      return;
    }

    Map<String, Set<String>> existingByTuple =
        queryExistingFilenames(
            attachmentEntity, parentKeyNames, tupleToKeys, tupleToIncoming, persistence);

    for (CdsData item : data) {
      String fileName = (String) item.get(Attachments.FILE_NAME);
      if (fileName == null) {
        continue;
      }
      Map<String, Object> tuple = extractTuple(item, parentKeyNames);
      if (tuple.isEmpty()) {
        continue;
      }
      String tupleKey = tupleKey(tuple, parentKeyNames);
      Set<String> existing = existingByTuple.getOrDefault(tupleKey, new HashSet<>());
      String renamed = resolveUniqueName(fileName, existing);
      if (!renamed.equals(fileName)) {
        logger.debug("Renaming attachment '{}' → '{}' to avoid duplicate", fileName, renamed);
        item.put(Attachments.FILE_NAME, renamed);
      }
      existing.add(renamed);
    }
  }

  private static void deduplicateCompositionChildren(
      CdsEntity entity, List<CdsData> data, PersistenceService persistence) {

    entity
        .compositions()
        .forEach(
            assocElement -> {
              CdsEntity childEntity =
                  assocElement.getType().as(CdsAssociationType.class).getTarget();
              if (!ApplicationHandlerHelper.isDirectMediaEntity(childEntity)) {
                return;
              }
              String assocName = assocElement.getName();
              List<String> parentKeyNames = getParentKeyNames(childEntity);
              if (parentKeyNames.isEmpty()) {
                return;
              }

              List<CdsData> allChildAttachments = new ArrayList<>();
              Map<String, Map<String, Object>> tupleToKeys = new HashMap<>();
              Map<String, Set<String>> tupleToIncoming = new HashMap<>();

              for (CdsData parentRow : data) {
                @SuppressWarnings("unchecked")
                List<CdsData> children = (List<CdsData>) parentRow.get(assocName);
                if (children == null) {
                  continue;
                }
                for (CdsData child : children) {
                  allChildAttachments.add(child);
                  String fileName = (String) child.get(Attachments.FILE_NAME);
                  if (fileName == null) {
                    continue;
                  }
                  Map<String, Object> tuple = extractTuple(child, parentKeyNames);
                  if (tuple.isEmpty()) {
                    tuple = extractParentTuple(parentRow, parentKeyNames);
                  }
                  if (tuple.isEmpty()) {
                    continue;
                  }
                  String tupleKey = tupleKey(tuple, parentKeyNames);
                  tupleToKeys.putIfAbsent(tupleKey, tuple);
                  tupleToIncoming.computeIfAbsent(tupleKey, k -> new HashSet<>()).add(fileName);
                }
              }

              if (tupleToKeys.isEmpty()) {
                return;
              }

              Map<String, Set<String>> existingByTuple =
                  queryExistingFilenames(
                      childEntity, parentKeyNames, tupleToKeys, tupleToIncoming, persistence);

              for (CdsData child : allChildAttachments) {
                String fileName = (String) child.get(Attachments.FILE_NAME);
                if (fileName == null) {
                  continue;
                }
                Map<String, Object> tuple = extractTuple(child, parentKeyNames);
                if (tuple.isEmpty()) {
                  for (CdsData parentRow : data) {
                    @SuppressWarnings("unchecked")
                    List<CdsData> children = (List<CdsData>) parentRow.get(assocName);
                    if (children != null && children.contains(child)) {
                      tuple = extractParentTuple(parentRow, parentKeyNames);
                      break;
                    }
                  }
                }
                if (tuple.isEmpty()) {
                  continue;
                }
                String tupleKey = tupleKey(tuple, parentKeyNames);
                Set<String> existing = existingByTuple.getOrDefault(tupleKey, new HashSet<>());
                String renamed = resolveUniqueName(fileName, existing);
                if (!renamed.equals(fileName)) {
                  logger.debug(
                      "Renaming attachment '{}' → '{}' to avoid duplicate", fileName, renamed);
                  child.put(Attachments.FILE_NAME, renamed);
                }
                existing.add(renamed);
              }
            });
  }

  // ---------------------------------------------------------------------------
  // Inline attachment fields
  // ---------------------------------------------------------------------------

  private static void deduplicateInlineAttachments(
      CdsEntity entity, List<CdsData> data, PersistenceService persistence) {

    List<String> prefixes = ApplicationHandlerHelper.getInlineAttachmentFieldNames(entity);
    if (prefixes.isEmpty()) {
      return;
    }

    List<String> entityKeyNames =
        entity.keyElements().map(k -> k.getName()).collect(Collectors.toList());

    for (String prefix : prefixes) {
      String fileNameField = prefix + "_" + Attachments.FILE_NAME;

      Map<String, Map<String, Object>> tupleToKeys = new HashMap<>();
      Map<String, Set<String>> tupleToIncoming = new HashMap<>();

      for (CdsData row : data) {
        String fileName = (String) row.get(fileNameField);
        if (fileName == null) {
          continue;
        }
        Map<String, Object> tuple = extractTuple(row, entityKeyNames);
        String tupleKey = tupleKey(tuple, entityKeyNames);
        tupleToKeys.putIfAbsent(tupleKey, tuple);
        tupleToIncoming.computeIfAbsent(tupleKey, k -> new HashSet<>()).add(fileName);
      }

      if (tupleToKeys.isEmpty()) {
        continue;
      }

      Map<String, Set<String>> existingByTuple =
          queryExistingInlineFilenames(
              entity, fileNameField, entityKeyNames, tupleToKeys, tupleToIncoming, persistence);

      for (CdsData row : data) {
        String fileName = (String) row.get(fileNameField);
        if (fileName == null) {
          continue;
        }
        Map<String, Object> tuple = extractTuple(row, entityKeyNames);
        String tupleKey = tupleKey(tuple, entityKeyNames);
        Set<String> existing = existingByTuple.getOrDefault(tupleKey, new HashSet<>());
        String renamed = resolveUniqueName(fileName, existing);
        if (!renamed.equals(fileName)) {
          logger.debug(
              "Renaming inline attachment '{}' → '{}' (field: {}) to avoid duplicate",
              fileName,
              renamed,
              fileNameField);
          row.put(fileNameField, renamed);
        }
        existing.add(renamed);
      }
    }
  }

  // ---------------------------------------------------------------------------
  // DB query helpers
  // ---------------------------------------------------------------------------

  private static Map<String, Set<String>> queryExistingFilenames(
      CdsEntity attachmentEntity,
      List<String> parentKeyNames,
      Map<String, Map<String, Object>> tupleToKeys,
      Map<String, Set<String>> tupleToIncoming,
      PersistenceService persistence) {

    Set<String> allIncoming = new HashSet<>();
    tupleToIncoming.values().forEach(allIncoming::addAll);

    List<CqnSelectListItem> columns = new ArrayList<>();
    columns.add(CQL.get(Attachments.FILE_NAME));
    parentKeyNames.forEach(k -> columns.add(CQL.get(k)));

    CqnPredicate where = buildCompositeWhere(parentKeyNames, tupleToKeys.values(), allIncoming);

    return runQueryAndGroup(
        persistence,
        attachmentEntity.getQualifiedName(),
        columns,
        where,
        Attachments.FILE_NAME,
        parentKeyNames);
  }

  private static Map<String, Set<String>> queryExistingInlineFilenames(
      CdsEntity entity,
      String fileNameField,
      List<String> entityKeyNames,
      Map<String, Map<String, Object>> tupleToKeys,
      Map<String, Set<String>> tupleToIncoming,
      PersistenceService persistence) {

    Set<String> allIncoming = new HashSet<>();
    tupleToIncoming.values().forEach(allIncoming::addAll);

    List<CqnSelectListItem> columns = new ArrayList<>();
    columns.add(CQL.get(fileNameField));
    entityKeyNames.forEach(k -> columns.add(CQL.get(k)));

    CqnPredicate where = buildCompositeWhere(entityKeyNames, tupleToKeys.values(), allIncoming);

    return runQueryAndGroup(
        persistence, entity.getQualifiedName(), columns, where, fileNameField, entityKeyNames);
  }

  private static Map<String, Set<String>> runQueryAndGroup(
      PersistenceService persistence,
      String entityName,
      List<CqnSelectListItem> columns,
      CqnPredicate where,
      String fileNameField,
      List<String> keyNames) {

    Map<String, Set<String>> result = new HashMap<>();
    List<CdsData> rows =
        persistence
            .run(Select.from(entityName).columns(columns).where(where))
            .listOf(CdsData.class);

    for (CdsData row : rows) {
      String fileName = (String) row.get(fileNameField);
      if (fileName == null) {
        continue;
      }
      Map<String, Object> tuple = extractTuple(row, keyNames);
      String tupleKey = tupleKey(tuple, keyNames);
      result.computeIfAbsent(tupleKey, k -> new HashSet<>()).add(fileName);
    }
    return result;
  }

  /**
   * Builds a CQN predicate of the form:
   *
   * <pre>
   *   ((k1=v1) or (k1=v2) ...)
   *   and
   *   ((fileName=? or fileName like ?) or ...)
   * </pre>
   */
  private static CqnPredicate buildCompositeWhere(
      List<String> keyNames, Iterable<Map<String, Object>> tuples, Set<String> allIncoming) {

    // Parent key filter
    CqnPredicate parentFilter = null;
    for (Map<String, Object> tuple : tuples) {
      CqnPredicate tuplePred = buildTuplePredicate(keyNames, tuple);
      parentFilter = parentFilter == null ? tuplePred : CQL.or(parentFilter, tuplePred);
    }

    // Filename filter
    CqnPredicate filenameFilter = null;
    for (String fileName : allIncoming) {
      CqnPredicate exact = CQL.get(Attachments.FILE_NAME).eq(fileName);
      String likePattern = buildLikePattern(fileName);
      CqnPredicate filePred =
          likePattern != null
              ? CQL.or(exact, CQL.get(Attachments.FILE_NAME).like(likePattern))
              : exact;
      filenameFilter = filenameFilter == null ? filePred : CQL.or(filenameFilter, filePred);
    }

    if (parentFilter == null || filenameFilter == null) {
      return parentFilter != null ? parentFilter : filenameFilter;
    }
    return CQL.and(parentFilter, filenameFilter);
  }

  private static CqnPredicate buildTuplePredicate(
      List<String> keyNames, Map<String, Object> tuple) {
    CqnPredicate pred = null;
    for (String key : keyNames) {
      CqnPredicate keyPred = CQL.get(key).eq(tuple.get(key));
      pred = pred == null ? keyPred : CQL.and(pred, keyPred);
    }
    return pred;
  }

  // ---------------------------------------------------------------------------
  // Rename logic
  // ---------------------------------------------------------------------------

  /**
   * Returns a filename not present in {@code existing}. If not a collision, returns unchanged;
   * otherwise appends {@code -1}, {@code -2}, etc.
   */
  static String resolveUniqueName(String fileName, Set<String> existing) {
    if (!existing.contains(fileName)) {
      return fileName;
    }
    int dotIndex = fileName.lastIndexOf('.');
    String base = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
    String ext = dotIndex > 0 ? fileName.substring(dotIndex) : "";
    int counter = 1;
    String candidate;
    do {
      candidate = base + "-" + counter + ext;
      counter++;
    } while (existing.contains(candidate));
    return candidate;
  }

  private static String buildLikePattern(String fileName) {
    int dotIndex = fileName.lastIndexOf('.');
    if (dotIndex <= 0) {
      return fileName + "-%";
    }
    return fileName.substring(0, dotIndex) + "-%" + fileName.substring(dotIndex);
  }

  // ---------------------------------------------------------------------------
  // Key helpers
  // ---------------------------------------------------------------------------

  private static List<String> getParentKeyNames(CdsEntity attachmentEntity) {
    return attachmentEntity
        .elements()
        .filter(e -> e.getName().startsWith("up__"))
        .map(e -> e.getName())
        .collect(Collectors.toList());
  }

  private static Map<String, Object> extractTuple(CdsData data, List<String> keyNames) {
    Map<String, Object> tuple = new HashMap<>();
    for (String key : keyNames) {
      Object value = data.get(key);
      if (value != null) {
        tuple.put(key, value);
      }
    }
    return tuple;
  }

  /**
   * For deep inserts the FK fields on the child are not yet set. Derive them from the parent row
   * (e.g. {@code up__ID} from the parent's {@code ID}).
   */
  private static Map<String, Object> extractParentTuple(
      CdsData parentRow, List<String> parentKeyNames) {
    Map<String, Object> tuple = new HashMap<>();
    for (String upKey : parentKeyNames) {
      String parentField = upKey.startsWith("up__") ? upKey.substring(4) : upKey;
      Object value = parentRow.get(parentField);
      if (value != null) {
        tuple.put(upKey, value);
      }
    }
    return tuple;
  }

  private static String tupleKey(Map<String, Object> tuple, List<String> keyNames) {
    StringBuilder sb = new StringBuilder();
    for (String key : keyNames) {
      sb.append(tuple.get(key)).append("|");
    }
    return sb.toString();
  }

  private FilenameDeduplicator() {
    // avoid instantiation
  }
}

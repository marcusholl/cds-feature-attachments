/*
 * © 2024-2026 SAP SE or an SAP affiliate company and cds-feature-attachments contributors.
 */
package com.sap.cds.feature.attachments.handler.applicationservice.helper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sap.cds.CdsData;
import com.sap.cds.Result;
import com.sap.cds.feature.attachments.generated.cds4j.sap.attachments.Attachments;
import com.sap.cds.feature.attachments.generated.test.cds4j.unit.test.testservice.RootTable;
import com.sap.cds.feature.attachments.generated.test.cds4j.unit.test.testservice.RootTable_;
import com.sap.cds.feature.attachments.handler.helper.RuntimeHelper;
import com.sap.cds.ql.cqn.CqnSelect;
import com.sap.cds.reflect.CdsEntity;
import com.sap.cds.reflect.CdsModel;
import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.runtime.CdsRuntime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FilenameDeduplicatorTest {

  private static CdsRuntime runtime;
  private static CdsModel model;

  private PersistenceService persistence;

  @BeforeAll
  static void classSetup() {
    runtime = RuntimeHelper.runtime;
    model = runtime.getCdsModel();
  }

  @BeforeEach
  void setup() {
    persistence = mock(PersistenceService.class);
    Result emptyResult = mock(Result.class);
    when(emptyResult.listOf(CdsData.class)).thenReturn(List.of());
    when(persistence.run(any(CqnSelect.class))).thenReturn(emptyResult);
  }

  // ---------------------------------------------------------------------------
  // resolveUniqueName — pure rename logic
  // ---------------------------------------------------------------------------

  @Test
  void resolveUniqueName_noCollision_unchanged() {
    String result = FilenameDeduplicator.resolveUniqueName("report.pdf", Set.of("other.pdf"));
    assertThat(result).isEqualTo("report.pdf");
  }

  @Test
  void resolveUniqueName_exactCollision_appendsSuffix1() {
    String result = FilenameDeduplicator.resolveUniqueName("report.pdf", Set.of("report.pdf"));
    assertThat(result).isEqualTo("report-1.pdf");
  }

  @Test
  void resolveUniqueName_suffix1AlsoExists_appendsSuffix2() {
    String result =
        FilenameDeduplicator.resolveUniqueName("report.pdf", Set.of("report.pdf", "report-1.pdf"));
    assertThat(result).isEqualTo("report-2.pdf");
  }

  @Test
  void resolveUniqueName_noExtension_appendsSuffix() {
    String result = FilenameDeduplicator.resolveUniqueName("report", Set.of("report"));
    assertThat(result).isEqualTo("report-1");
  }

  @Test
  void resolveUniqueName_emptySuffix_noCollision() {
    String result = FilenameDeduplicator.resolveUniqueName("report.pdf", Set.of());
    assertThat(result).isEqualTo("report.pdf");
  }

  // ---------------------------------------------------------------------------
  // deduplicate — composition entity, no collision
  // ---------------------------------------------------------------------------

  @Test
  void deduplicate_compositionNoExistingFiles_noRename() {
    CdsEntity rootEntity = model.findEntity(RootTable_.CDS_NAME).orElseThrow();

    String parentId = UUID.randomUUID().toString();
    RootTable root = RootTable.create();
    root.setId(parentId);

    Attachments attachment = Attachments.create();
    attachment.setFileName("report.pdf");
    attachment.put("up__ID", parentId);
    root.setAttachments(List.of(attachment));

    FilenameDeduplicator.deduplicate(model, rootEntity, List.of(root), persistence);

    assertThat(attachment.getFileName()).isEqualTo("report.pdf");
  }

  @Test
  void deduplicate_compositionExistingFileCollides_renames() {
    CdsEntity rootEntity = model.findEntity(RootTable_.CDS_NAME).orElseThrow();

    String parentId = UUID.randomUUID().toString();

    // Simulate DB returning "report.pdf" as existing for this parent
    CdsData existing = CdsData.create();
    existing.put(Attachments.FILE_NAME, "report.pdf");
    existing.put("up__ID", parentId);

    Result dbResult = mock(Result.class);
    when(dbResult.listOf(CdsData.class)).thenReturn(List.of(existing));
    when(persistence.run(any(CqnSelect.class))).thenReturn(dbResult);

    RootTable root = RootTable.create();
    root.setId(parentId);

    Attachments attachment = Attachments.create();
    attachment.setFileName("report.pdf");
    attachment.put("up__ID", parentId);
    root.setAttachments(List.of(attachment));

    FilenameDeduplicator.deduplicate(model, rootEntity, List.of(root), persistence);

    assertThat(attachment.getFileName()).isEqualTo("report-1.pdf");
  }

  @Test
  void deduplicate_twoAttachmentsInOneRequest_secondAlsoRenamed() {
    CdsEntity rootEntity = model.findEntity(RootTable_.CDS_NAME).orElseThrow();

    String parentId = UUID.randomUUID().toString();

    // DB has "report.pdf" already
    CdsData existing = CdsData.create();
    existing.put(Attachments.FILE_NAME, "report.pdf");
    existing.put("up__ID", parentId);

    Result dbResult = mock(Result.class);
    when(dbResult.listOf(CdsData.class)).thenReturn(List.of(existing));
    when(persistence.run(any(CqnSelect.class))).thenReturn(dbResult);

    RootTable root = RootTable.create();
    root.setId(parentId);

    Attachments a1 = Attachments.create();
    a1.setFileName("report.pdf");
    a1.put("up__ID", parentId);

    Attachments a2 = Attachments.create();
    a2.setFileName("report.pdf");
    a2.put("up__ID", parentId);

    root.setAttachments(List.of(a1, a2));

    FilenameDeduplicator.deduplicate(model, rootEntity, List.of(root), persistence);

    // first gets -1, second gets -2 (because -1 is already taken after the first rename)
    assertThat(a1.getFileName()).isEqualTo("report-1.pdf");
    assertThat(a2.getFileName()).isEqualTo("report-2.pdf");
  }

  @Test
  void deduplicate_differentFileNoCollision_unchanged() {
    CdsEntity rootEntity = model.findEntity(RootTable_.CDS_NAME).orElseThrow();

    String parentId = UUID.randomUUID().toString();

    CdsData existing = CdsData.create();
    existing.put(Attachments.FILE_NAME, "other.pdf");
    existing.put("up__ID", parentId);

    Result dbResult = mock(Result.class);
    when(dbResult.listOf(CdsData.class)).thenReturn(List.of(existing));
    when(persistence.run(any(CqnSelect.class))).thenReturn(dbResult);

    RootTable root = RootTable.create();
    root.setId(parentId);

    Attachments attachment = Attachments.create();
    attachment.setFileName("report.pdf");
    attachment.put("up__ID", parentId);
    root.setAttachments(List.of(attachment));

    FilenameDeduplicator.deduplicate(model, rootEntity, List.of(root), persistence);

    assertThat(attachment.getFileName()).isEqualTo("report.pdf");
  }

  @Test
  void deduplicate_nullFileName_noInteractionWithDb() {
    CdsEntity rootEntity = model.findEntity(RootTable_.CDS_NAME).orElseThrow();

    String parentId = UUID.randomUUID().toString();
    RootTable root = RootTable.create();
    root.setId(parentId);

    Attachments attachment = Attachments.create();
    // no filename set
    attachment.put("up__ID", parentId);
    root.setAttachments(List.of(attachment));

    FilenameDeduplicator.deduplicate(model, rootEntity, List.of(root), persistence);

    // Nothing should be renamed and DB should not be queried
    assertThat(attachment.getFileName()).isNull();
  }

  // ---------------------------------------------------------------------------
  // deduplicate — inline attachment fields
  // ---------------------------------------------------------------------------

  @Test
  void deduplicate_inlineNoCollision_unchanged() {
    String inlineEntity = "unit.test.TestService.InlineOnly";
    CdsEntity entity = model.findEntity(inlineEntity).orElseThrow();

    CdsData row = CdsData.create();
    row.put("ID", UUID.randomUUID().toString());
    row.put("avatar_fileName", "photo.png");

    FilenameDeduplicator.deduplicate(model, entity, List.of(row), persistence);

    assertThat(row.get("avatar_fileName")).isEqualTo("photo.png");
  }

  @Test
  void deduplicate_inlineExistingCollides_renames() {
    String inlineEntity = "unit.test.TestService.InlineOnly";
    CdsEntity entity = model.findEntity(inlineEntity).orElseThrow();

    String id = UUID.randomUUID().toString();

    CdsData existing = CdsData.create();
    existing.put("avatar_fileName", "photo.png");
    existing.put("ID", id);

    Result dbResult = mock(Result.class);
    when(dbResult.listOf(CdsData.class)).thenReturn(List.of(existing));
    when(persistence.run(any(CqnSelect.class))).thenReturn(dbResult);

    CdsData row = CdsData.create();
    row.put("ID", id);
    row.put("avatar_fileName", "photo.png");

    FilenameDeduplicator.deduplicate(model, entity, List.of(row), persistence);

    assertThat(row.get("avatar_fileName")).isEqualTo("photo-1.png");
  }
}

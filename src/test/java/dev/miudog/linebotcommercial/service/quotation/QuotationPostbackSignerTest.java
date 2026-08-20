package dev.miudog.linebotcommercial.service.quotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class QuotationPostbackSignerTest {

	private static final String SECRET = "0123456789abcdef0123456789abcdef";
	private static final Instant NOW = Instant.parse("2026-08-11T07:00:00Z");
	private static final String OWNER_ID = "U-sensitive-line-user-id";

	// 方法：簽章資料包含必要狀態且不暴露使用者或密鑰。
	@Test
	void signsCompactPostbackWithoutSensitiveInformation() {
		QuotationPostbackSigner signer = signerAt(NOW);

		String data = signer.sign(42L, 7, QuotationPostbackAction.CONFIRM, NOW.plusSeconds(900), OWNER_ID);
		QuotationVerifiedPostback verified = signer.verify(data, OWNER_ID, 7);

		assertThat(data).hasSizeLessThanOrEqualTo(300);
		assertThat(data).doesNotContain(OWNER_ID, SECRET);
		assertThat(verified.draftId()).isEqualTo(42L);
		assertThat(verified.revision()).isEqualTo(7);
		assertThat(verified.action()).isEqualTo(QuotationPostbackAction.CONFIRM);
		assertThat(verified.expiresAt()).isEqualTo(NOW.plusSeconds(900));
	}

	// 測試：候選圖片識別也必須受到使用者、版本、期限與 HMAC 的完整保護。
	@Test
	void signsAndVerifiesCandidateImageSelection() {
		QuotationPostbackSigner signer = signerAt(NOW);

		String data = signer.sign(
			42L,
			7,
			QuotationPostbackAction.SELECT_IMAGE,
			NOW.plusSeconds(900),
			OWNER_ID,
			"LINE-IMAGE-002"
		);
		QuotationVerifiedPostback verified = signer.verify(data, OWNER_ID, 7);

		assertThat(verified.action()).isEqualTo(QuotationPostbackAction.SELECT_IMAGE);
		assertThat(verified.resourceId()).isEqualTo("LINE-IMAGE-002");
		assertThat(data).doesNotContain("LINE-IMAGE-002", OWNER_ID, SECRET);
	}

	// 測試：候選頁碼同樣納入簽章，不接受未驗證的翻頁參數。
	@Test
	void signsAndVerifiesCandidateOptionsPage() {
		QuotationPostbackSigner signer = signerAt(NOW);

		String data = signer.sign(
			42L,
			7,
			QuotationPostbackAction.IMAGE_OPTIONS_PAGE,
			NOW.plusSeconds(900),
			OWNER_ID,
			"1"
		);

		assertThat(signer.verify(data, OWNER_ID, 7).resourceId()).isEqualTo("1");
	}

	// 方法：任何 postback 欄位被竄改時拒絕執行。
	@Test
	void rejectsTamperedAction() {
		QuotationPostbackSigner signer = signerAt(NOW);
		String data = signer.sign(42L, 7, QuotationPostbackAction.CONFIRM, NOW.plusSeconds(900), OWNER_ID);
		String tampered = data.replace("a=c", "a=x");

		assertThatThrownBy(() -> signer.verify(tampered, OWNER_ID, 7))
			.isInstanceOf(QuotationPostbackException.class)
			.extracting(error -> ((QuotationPostbackException) error).code())
			.isEqualTo("INVALID_SIGNATURE");
	}

	// 方法：超過有效期限的 postback 不得繼續使用。
	@Test
	void rejectsExpiredPostback() {
		QuotationPostbackSigner signingSigner = signerAt(NOW);
		String data = signingSigner.sign(42L, 7, QuotationPostbackAction.CANCEL, NOW.plusSeconds(60), OWNER_ID);
		QuotationPostbackSigner verifyingSigner = signerAt(NOW.plusSeconds(61));

		assertThatThrownBy(() -> verifyingSigner.verify(data, OWNER_ID, 7))
			.isInstanceOf(QuotationPostbackException.class)
			.extracting(error -> ((QuotationPostbackException) error).code())
			.isEqualTo("POSTBACK_EXPIRED");
	}

	// 方法：舊 revision 的按鈕不得修改新版草稿。
	@Test
	void rejectsStaleRevision() {
		QuotationPostbackSigner signer = signerAt(NOW);
		String data = signer.sign(42L, 7, QuotationPostbackAction.CANCEL, NOW.plusSeconds(900), OWNER_ID);

		assertThatThrownBy(() -> signer.verify(data, OWNER_ID, 8))
			.isInstanceOf(QuotationPostbackException.class)
			.extracting(error -> ((QuotationPostbackException) error).code())
			.isEqualTo("STALE_REVISION");
	}

	// 方法：轉傳給其他 LINE 使用者的 postback 不得通過所有權驗證。
	@Test
	void rejectsDifferentOwner() {
		QuotationPostbackSigner signer = signerAt(NOW);
		String data = signer.sign(42L, 7, QuotationPostbackAction.CANCEL, NOW.plusSeconds(900), OWNER_ID);

		assertThatThrownBy(() -> signer.verify(data, "U-other-user", 7))
			.isInstanceOf(QuotationPostbackException.class)
			.extracting(error -> ((QuotationPostbackException) error).code())
			.isEqualTo("OWNER_MISMATCH");
	}

	@Test
	void doesNotQueryDraftRevisionUntilSignatureAndOwnerAreVerified() {
		QuotationPostbackSigner signer = signerAt(NOW);
		String data = signer.sign(42L, 7, QuotationPostbackAction.CANCEL, NOW.plusSeconds(900), OWNER_ID);
		AtomicBoolean queried = new AtomicBoolean(false);

		assertThatThrownBy(() -> signer.verify(data, "U-other-user", draftId -> {
			queried.set(true);
			return 7;

		}))
			.isInstanceOf(QuotationPostbackException.class)
			.extracting(error -> ((QuotationPostbackException) error).code())
			.isEqualTo("OWNER_MISMATCH");
		assertThat(queried).isFalse();
	}

	// 方法：建立固定時間的簽章器以驗證期限行為。
	private QuotationPostbackSigner signerAt(Instant instant) {
		return new QuotationPostbackSigner(SECRET, Clock.fixed(instant, ZoneOffset.UTC));
	}
}

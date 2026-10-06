package com.inmc.achievements.config

import com.inmc.achievements.util.Ph
import kr.inmc.core.config.MessageCatalog
import org.bukkit.configuration.file.YamlConfiguration

/**
 * `messages.yml` 한 벌.
 *
 * 읽고 보내는 부분은 전부 core 의 [MessageCatalog] 가 갖고 있다. 남는 것은 기본값 표뿐이고
 * 그게 이 플러그인의 도메인이다.
 *
 * **배포되는 `messages.yml` 은 이 표에서 생성한다.** 양쪽이 갈라지면 관리자가 고쳐도 안
 * 나오거나 코드가 찾는 키가 파일에 없다. `ResourceTest` 가 정확한 일치를 지킨다.
 */
class Messages(values: Map<String, String>) : MessageCatalog<Ph>(values, DEFAULTS) {

    companion object {

        fun from(config: YamlConfiguration): Messages = Messages(merge(DEFAULTS, config))

        val DEFAULTS: Map<String, String> = mapOf(
            PREFIX to "<gradient:#ffd479:#ffb347>[ 업적 ]</gradient> ",

            // --- 달성 ---------------------------------------------------------------
            "unlocked" to "<gold>업적 달성!</gold> <white>{업적}</white>",
            "unlocked-tier" to "<gold>업적 달성!</gold> <white>{업적} {단계}</white>",
            "broadcast" to "<yellow>{플레이어}</yellow> <gray>님이</gray> <white>{업적}</white> <gray>업적을 달성했습니다.</gray>",
            "first-clear" to "<gold>서버 최초!</gold> <yellow>{플레이어}</yellow> <gray>님이</gray> <white>{업적}</white> <gray>을(를) 처음으로 달성했습니다.</gray>",
            "discovered" to "<light_purple>숨겨진 업적을 발견했습니다:</light_purple> <white>{업적}</white>",
            "points-gained" to "<gray>업적 점수 <yellow>+{점수}</yellow></gray>",
            "rank-up" to "<gold>업적 랭크가 올랐습니다:</gold> <white>{랭크}</white>",

            // --- 보상 ---------------------------------------------------------------
            "reward-mailed" to "<yellow>보상을 우편함에 보관했습니다. /업적 우편함 으로 받으세요.</yellow>",
            "reward-deferred" to "<gray>접속 후 지급되는 보상이 있어 지금 처리했습니다.</gray>",
            "reward-failed" to "<red>보상을 지급하지 못했습니다: {사유}</red>",

            // --- 조회 ---------------------------------------------------------------
            "progress" to "<white>{업적}</white> <gray>—</gray> <yellow>{진행도}</yellow><gray>/{목표}</gray> <gray>({달성률}%)</gray>",
            "not-found" to "<red>그런 업적이 없습니다.</red>",
            "not-discovered" to "<dark_gray>??? — 아직 발견하지 못한 업적입니다.</dark_gray>",
            "stats-header" to "<gold>{플레이어} 님의 업적</gold>",
            "stats-line" to "<gray>{분류}: <white>{진행도}</white>/{목표} <dark_gray>({달성률}%)</dark_gray></gray>",
            "stats-points" to "<gray>업적 점수: <yellow>{점수}</yellow> <dark_gray>(랭크 {랭크})</dark_gray></gray>",

            // --- 관리 ---------------------------------------------------------------
            "admin-created" to "<green>업적 '{업적}' 을(를) 만들었습니다.</green>",
            "admin-deleted" to "<yellow>업적 '{업적}' 을(를) 지웠습니다.</yellow>",
            "admin-delete-blocked" to "<red>'{업적}' 을(를) 가리키는 업적이 있어 지울 수 없습니다: {사유}</red>",
            "admin-exists" to "<red>'{업적}' 은(는) 이미 있습니다.</red>",
            "admin-invalid-id" to "<red>이름이 올바르지 않습니다. {사유}</red>",
            "admin-granted" to "<green>{플레이어} 님에게 '{업적}' 을(를) 지급했습니다.</green>",
            "admin-revoked" to "<yellow>{플레이어} 님의 '{업적}' 기록을 지웠습니다.</yellow>",
            "admin-reset" to "<yellow>{플레이어} 님의 업적 데이터를 초기화했습니다. (달성 {진행도}단계)</yellow>",
            "admin-reset-one" to "<yellow>{플레이어} 님의 '{업적}' 기록을 초기화했습니다. (달성 {진행도}단계)</yellow>",
            "admin-reset-empty" to "<gray>{플레이어} 님에게 지울 업적 데이터가 없습니다.</gray>",
            "admin-reloaded" to "<green>설정을 다시 읽었습니다. (업적 {진행도}개)</green>",
            "admin-unpaid" to "<yellow>지급 여부가 확인되지 않은 항목 {진행도}건이 있습니다.</yellow>",
            "admin-unpaid-none" to "<green>확인이 필요한 미지급 항목이 없습니다.</green>",
            "admin-rebuilding" to "<gray>정의가 바뀌어 기록을 다시 계산하고 있습니다...</gray>",
            "admin-rebuilt" to "<green>기록 재계산을 마쳤습니다. (플레이어 {진행도}명)</green>",

            // --- 공통 ---------------------------------------------------------------
            "no-permission" to "<red>권한이 없습니다.</red>",
            "player-only" to "<red>게임 안에서만 쓸 수 있습니다.</red>",
            "player-not-found" to "<red>그런 플레이어를 찾을 수 없습니다.</red>",
            "not-ready" to "<red>아직 준비 중입니다. 잠시 후 다시 시도해주세요.</red>",

            // --- core ChatPrompt 가 요구하는 네 키. 없으면 프롬프트가 조용히 무음이 된다 ---
            "prompt-enter" to "<yellow>채팅에 값을 입력하세요. <gray>(취소: 취소)</gray></yellow>",
            "prompt-cancelled" to "<gray>입력을 취소했습니다.</gray>",
            "prompt-timeout" to "<gray>입력 시간이 지났습니다.</gray>",
            "prompt-invalid-number" to "<red>숫자로 입력해주세요.</red>",
        )
    }
}

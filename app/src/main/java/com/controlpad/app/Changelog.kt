package com.controlpad.app

import android.app.Activity
import android.app.AlertDialog
import android.widget.ScrollView
import android.widget.TextView

/**
 * 버전별로 바뀐 점. 새 버전을 낼 때 맨 위에 한 항목을 더한다(맨 위 항목의 버전은 빌드 버전과 같아야 한다).
 * 예전에 1.x 로 불리던 버전은 BETA, 2.x 로 불리던 버전은 1.x 다.
 */
object Changelog {
    class Entry(val version: String, val notes: List<String>)

    val entries = listOf(
        Entry("1.30", listOf("실험실: 옵션 버튼 — 실행 중에 진동, 키 표시, 실험실 기능 같은 설정을 버튼으로 켜고 끄기 (알파)", "설정: 실험실 · 평가단 버튼을 맨 위로")),
        Entry("1.29", listOf("평가단: 실험실 기능 정식 추가 건의는 10분 뒤 알파 사용자 모두에게, 그로부터 20분 뒤 정식으로 반영 (알파)", "평가단: 빌드 정식 추가 건의는 30분 뒤 반영")),
        Entry("1.28", listOf("그리기: 그린 그림을 일반 버튼뿐 아니라 노브, 조이스틱, 트랙패드, 가상 마우스, 표시 버튼, 레이아웃 전환 버튼, 표시 패널로 만들기")),
        Entry("1.27", listOf("표시 패널: 키뿐 아니라 초당 입력 수, 콤보, 총 입력 수, 실행 시간도 원하는 위치·크기·색으로 넣기", "＋추가에 '초당 입력 수 패널' 추가")),
        Entry("1.26", listOf("평가단: 실험실 기능 숨김 건의와 복귀 건의 (알파)")),
        Entry("1.25", listOf("실험실: 최대 밝기, 연결 상태 숨기기, 누를 때 소리, 최근 입력 기록, 편집 화면 위치·크기 표시 (알파)")),
        Entry("1.24", listOf("1.23 에서 추가한 기능 10개(검색, 고정, 홈 바로가기, 전체 선택, 맞춤, 균등 배치, 잠금, 불투명도, 글자 크기, 나가기 방지)를 실험실로 옮김 (알파)")),
        Entry("1.23", listOf(
            "목록: 레이아웃 검색, 맨 위에 고정, 홈 화면에 바로가기 추가",
            "편집: 전체 선택, 맞춤, 균등 배치·같은 크기, 버튼 잠금",
            "버튼 속성: 불투명도, 글자 크기",
            "설정: 실수로 나가기 방지",
            "실험실: 콤보, 총 입력 수, 실행 시간, 배터리·시각 표시, 강한 진동 (알파)",
        )),
        Entry("1.22", listOf("평가단: 중계 서버가 설정되어 있으면 GitHub 로그인 없이 평가단 코드만으로 정식 추가 요청 (알파)")),
        Entry("1.21", listOf("편집 화면 새 단장: 왼쪽에 묶음별 도구 모음, 위쪽에 저장·실행·되돌리기", "그리기를 편집 화면에 합침: 그림 버튼을 그리면서 다른 편집 기능을 모두 사용", "실험실: 다듬는 중인 기능을 켜 보기 (알파)", "평가단: 알파 빌드와 실험실 기능의 정식 추가 요청 (알파)")),
        Entry("1.20", listOf("매크로 버튼: 누르면 여러 키 조합을 차례로 입력", "연타 버튼: 누르고 있는 동안 빠르게 반복 입력", "레이아웃 전환 버튼: 실행 화면에서 다른 레이아웃으로 바로 넘어감", "앱 이름을 Control Pad 로 변경")),
        Entry("1.19", listOf("목록 화면의 '그림 레이아웃' 버튼으로 그림 에디터를 바로 엽니다")),
        Entry("1.18", listOf("테마 에디터: 나만의 테마를 여러 개 만들고 이름을 붙여 저장", "그림 에디터: 손으로 그린 그림이 그대로 버튼이 됨")),
        Entry("1.17.1", listOf("업데이트 내역 화면 추가 (설정 > 업데이트 내역)", "버전 번호 정리: 예전 1.x 는 BETA, 예전 2.x 는 1.x 로 부릅니다")),
        Entry("1.17", listOf("사진 넣기 (꾸미기용 사진, 버튼 면 사진)", "크기·위치를 숫자로 세밀하게 조정", "겹치는 순서 바꾸기")),
        Entry("1.16", listOf("키 입력 표시 패널: 눌리고 있는 키를 원하는 위치·크기·색으로 표시")),
        Entry("1.15", listOf("조이스틱 (방향키, WASD, 원하는 키)")),
        Entry("1.14.1", listOf("노브를 천천히 돌릴 때 키가 떼졌다 눌렸다 하던 문제 수정")),
        Entry("1.14", listOf("키보드 방식 노브에 '계속 누름' 추가")),
        Entry("1.13", listOf("앱 이름을 '리듬 컨트롤러/가상키보드'로 변경")),
        Entry("1.12", listOf("레이아웃을 파일로 저장하고 불러오기", "편집 화면에 저장 버튼 추가")),
        Entry("1.11", listOf("편집 화면에 실행취소·다시 실행, 임시 숨김 추가")),
        Entry("1.10", listOf("Wi-Fi 연결에서 PC 주소 자동으로 찾기", "여러 버튼을 선택한 채로 크기 바꾸기")),
        Entry("1.9.2", listOf("연결되어 있는데 연결 안 됨으로 표시되던 문제 수정")),
        Entry("1.9.1", listOf("버튼이 많은 레이아웃에서 반응이 느려지던 문제 수정", "Wi-Fi 방식에서 PC까지의 왕복 시간 표시")),
        Entry("1.9", listOf("다중 선택에서 손가락으로 훑어 여러 버튼 선택", "입력 지연 감소")),
        Entry("1.8", listOf("한/영을 누르면 한글 키보드의 글자 표시가 영문 ↔ 한글로 전환", "Wi-Fi 연결용 PC 수신 프로그램을 exe 로 배포")),
        Entry("1.7", listOf("＋추가에 영문 키보드, 한글 키보드 추가", "한/영, 한자 키 추가")),
        Entry("1.6", listOf("편집 화면의 추가 도구를 '＋추가' 메뉴로 모음")),
        Entry("1.5", listOf("기본 레이아웃에 키보드 추가")),
        Entry("1.4", listOf("숫자 키패드 추가")),
        Entry("1.3", listOf("누르면 정해 둔 시간 동안 키를 누르고 있는 버튼 추가")),
        Entry("1.2.2", listOf("색 고르기 창이 잘리던 문제 수정")),
        Entry("1.2.1", listOf("알파 빌드 받기를 켜도 업데이트가 오지 않던 문제 수정")),
        Entry("1.2", listOf("편집 화면에 다중 선택 추가", "버튼마다 글자색과 눌렸을 때 색 지정")),
        Entry("1.1", listOf("표시 버튼: 누르면 같은 그룹의 버튼들이 나타남")),
        Entry("1.0", listOf("노브에서 손을 떼면 방향 표시점이 원점으로 돌아옴")),
        Entry("BETA 1.9", listOf("눌리고 있는 키 표시와 버튼의 키 이름 표시 켜기/끄기")),
        Entry("BETA 1.8", listOf("테마 10종 추가, 커스텀 테마", "버튼 색 직접 고르기")),
        Entry("BETA 1.7", listOf("자동 업데이트")),
        Entry("BETA 1.6", listOf("테마 (다크, 블랙, 라이트, 네온)", "키 선택 창이 잘리던 문제와 테마가 일부 화면에만 적용되던 문제 수정")),
        Entry("BETA 1.5", listOf("퀵 리스타트 버튼")),
        Entry("BETA 1.4", listOf("업데이트 채널 분리 (알파, 정식)")),
        Entry("BETA 1.3", listOf("가상 마우스", "앱 안에서 업데이트")),
        Entry("BETA 1.2", listOf("가상 트랙패드, 마우스 버튼")),
        Entry("BETA 1.1", listOf("노브")),
        Entry("BETA 1.0", listOf("첫 버전: 버튼을 직접 그리는 편집 화면, 블루투스 키보드 연결, Wi-Fi 연결")),
    )

    /** 화면에 보여 줄 글. latestOnly 면 가장 최신 버전의 내용만. */
    fun text(latestOnly: Boolean = false): String =
        (if (latestOnly) entries.take(1) else entries).joinToString("\n\n") { e ->
            e.version + "\n" + e.notes.joinToString("\n") { "  · $it" }
        }
}

/** 업데이트 내역을 창으로 보여 준다. */
fun showChangelog(a: Activity, latestOnly: Boolean = false) {
    val pad = a.dp(20)
    val view = TextView(a).apply {
        text = Changelog.text(latestOnly); textSize = 15f; setLineSpacing(0f, 1.2f)
        setPadding(pad, a.dp(12), pad, 0); setTextIsSelectable(true)
    }
    AlertDialog.Builder(a)
        .setTitle(if (latestOnly) "업데이트되었습니다" else "업데이트 내역")
        .setView(ScrollView(a).apply { addView(view) })
        .setPositiveButton("확인", null)
        .apply { if (latestOnly) setNeutralButton("전체 내역") { _, _ -> showChangelog(a) } }
        .show()
}

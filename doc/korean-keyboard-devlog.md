# 두벌식 한글 IME 개발 로그 (Samsung 스타일 한/영 키보드)

> 이 문서는 LLM/개발자가 이후 작업을 이어가도록 남기는 세션 기록이다.
> 브랜치: `claude/unexpected-keyboard-lang-toggle-64f4c3` (master에도 반영).

## 목표

Unexpected Keyboard(안드로이드 IME) 위에 **삼성 키보드식 두벌식 한글 + 한/영 토글**을
얹는다. 실시간 음절 조합, 단일 스페이스로 음절 확정+띄어쓰기, 백스페이스 분해(간→가→ㄱ),
연음(간+ㅏ=가나), 복받침/복모음 자동 조합, Ctrl 단축키까지 삼성처럼 동작하게 한다.

## 아키텍처 (어디에 뭐가 있나)

| 파일 | 역할 |
|---|---|
| `srcs/juloo.keyboard2/HangulAutomaton.java` | 두벌식 조합 오토마타. 초성·중성·종성 상태기계. `setComposingText`/`commitText`로 IC와 통신. 복모음/복받침 자동 조합, 연음, 백스페이스 분해. |
| `srcs/juloo.keyboard2/KeyEventHandler.java` | `key_up`에서 한글 모드일 때 자모를 오토마타로 라우팅. 스페이스·엔터·Event·Editing 키 전에 조합 음절을 `commitKorean()`으로 확정. `setKoreanMode()` 보유. |
| `srcs/juloo.keyboard2/KeyModifier.java` | `apply_shift`(쌍자음 ㅂ→ㅃ, ㅐ→ㅒ 등), `turn_into_keyevent`(자모→QWERTY 키코드/라벨, Ctrl·Alt 단축키). `dubeolsik_to_latin` 헬퍼. |
| `srcs/juloo.keyboard2/Keyboard2.java` | `SWITCH_LANG` 이벤트 처리(레이아웃 인덱스 토글), `syncKoreanMode()`(layout.script로 한글모드 자동 감지). |
| `srcs/layouts/hang_dubeolsik_kr.xml` | 두벌식 레이아웃 (`script="hangul"`). 삼성식 자모 배열 + 모서리 숫자/기호. |
| `srcs/layouts/latn_qwerty_us.xml` | 영문 레이아웃. 기호 배치를 한글과 동일하게 미러링. |
| `res/xml/method.xml` | `ko` 서브타입의 `default_layout=hang_dubeolsik_kr` (시스템 세팅=한글일 때 이 자판). |
| `srcs/juloo.keyboard2/Config.java` | 기본 키보드 높이값(세로 30 / 가로 45). |

### 핵심 동작 원리
- **한/영 토글**: `_config.layouts`에서 현재 script의 반대(hangul↔latin) 레이아웃을 찾아
  `setTextLayout()`. `null`(시스템 세팅) 항목은 `_localeTextLayout`으로 해석 → 한국어
  서브타입도 감지. 스페이스 위로-스와이프(layout 순환)와 **같은 메커니즘**이라 상태가 안 어긋남.
- **한글 모드 감지**: `syncKoreanMode()`가 `layout.script == "hangul"`이면 자동 ON. 모든 전환
  경로가 이걸 거쳐서 "두 개의 다른 한글자판"이 안 생김.
- **Ctrl 단축키**: 자모는 `dubeolsik_to_latin`으로 같은 QWERTY 위치의 영문으로 변환된 뒤
  키이벤트화 → Ctrl 누르면 라벨도 영문(z·x·c·v)으로 바뀌고 Ctrl+ㅊ=복사 등 동작.

## 전달된 기능
- 한/영 토글 키(`switch_lang`, 라벨 "한/영") + 스페이스 스와이프 전환
- 실시간 두벌식 조합 / 백스페이스 분해(아→ㅇ, 가→ㄱ) / 연음 / 복받침·복모음 자동
- shift 쌍자음(ㅃㅉㄸㄲㅆ) + ㅒ/ㅖ (삼성과 동일)
- Tab / Shift+Tab(⇤ 매크로 `shift,tab`) — a·ㅁ 모서리, 클로드코드 모드전환용
- Ctrl/Alt: 한글자판에서 영문 라벨 표시 + 단축키(복사/붙여넣기/취소/잘라내기/전체선택)
- 영문 기호 배치를 한글과 동일하게(숫자=오른쪽위, 기호=왼쪽위, 문장부호=아래)
- 독립 delete 키, 그리스/수학 직접 스와이프 제거(123+ 경로는 유지), 마이크 제거

## 버그 수정 이력 (주요)
1. 자음이 `Hangul_initial`(FLAG_LATCH)이라 무시됨 → `makeCharKey`로 변경.
2. 한글 모드가 special-layout 경로에서 활성화 안 됨 → `syncKoreanMode` 도입.
3. 한/영 토글이 "시스템 세팅"(null) 한글을 못 찾아 special-layout으로 폴백, 두 종류 자판 발생
   → `null`을 `_localeTextLayout`으로 해석.
4. 조합 중 비-자모(기호/숫자)·Event(이모지 등)·Editing(복붙) 입력 시 글자 깨짐
   → 해당 경로 전에 `commitKorean()`.
5. 백스페이스 아→통째삭제 → 표준 분해(아→ㅇ).
6. change_method(⌨) 키가 한↔한처럼 IME를 바꿈 → 양쪽 레이아웃에서 제거.

### 의도적으로 스킵한 항목 (코드리뷰)
- special-overlay(숫자판) 떠 있을 때 토글 방향 판정 / 포커스 전환 타이밍 시 미확정 음절 →
  타이밍 꼬임 위험으로 보류.
- hangul-only 환경에서 토글 무동작 → QWERTY 동시 사용 환경에선 무관.

## 검증
- `/code-review`(high) 8앵글 → 발견 항목 중 실사용 버그 4 + 쉬운 2건 수정.
- `check_layout.output` 재생성(커스텀 bottom row 경고는 의도된 것으로 기록).
- 중괄호/괄호 균형 + KeyModifier 구조 수동 확인. 풀 컴파일은 CI(make-apk.yml)에서.

## 커밋 순서 (ccd9568 이후)
- `6a17c7b` switch_lang 키 추가
- `123d62d` HangulAutomaton 통합
- `9651336` 연음 batchEdit
- `fa2b67a` 자음 Char 타입화
- `ff01fc0` script 필드로 한글모드 동기화
- `aaa4453` 1차 리뷰 8버그 수정
- `8881080` check_layout.output 재생성
- `eb1b925` shift 쌍자음 + 비-자모 입력 전 확정
- `2f72768` 삼성식 한글 레이아웃 + 토글 재작성 + 정리
- `8ea63fe` Tab / Shift+Tab
- `94def60` 메타 제거, ㅖ/ㅙ 재배치, compose 모서리 복원
- `874ccee` 시스템(null) 한글 감지 + 영문 기호 미러링
- `0087454` Ctrl/Alt 영문 라벨+단축키
- `801e00e` change_method 키 제거
- `804d58d` 2차 리뷰 수정(전환 시 확정/백스페이스/esc/ctrl 쌍자음)
- `278946d` 기본 키보드 높이 축소(컴팩트)

## 알려진 튜닝 포인트
- 키보드 크기는 디바이스마다 다름 → 설정 **키보드 높이 / 가로 여백** 슬라이더로 미세조정.
  현재 기본값(세로 30 / 가로 45)은 태블릿 기준 컴팩트하게 잡은 시작점.

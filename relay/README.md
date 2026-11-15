# 평가단 중계 서버 설정

평가단이 GitHub 로그인 없이 앱에서 정식 추가를 요청할 수 있게 해 주는 작은 서버입니다.
한 번만 설정하면 되고, 무료 요금제로 충분합니다. 아래 순서는 모두 웹 브라우저에서 합니다.

설정하기 전에는 앱이 예전처럼 GitHub 이슈 화면을 엽니다.

## 1. GitHub 토큰 만들기

중계 서버가 이 저장소의 자동화를 실행할 때 쓰는 열쇠입니다.

1. GitHub에 로그인하고 `Settings > Developer settings > Personal access tokens > Fine-grained tokens`로 갑니다.
2. `Generate new token`을 누릅니다.
3. `Repository access`에서 `Only select repositories`를 고르고 `control` 저장소만 선택합니다.
4. `Permissions > Repository permissions`에서 `Actions`를 `Read and write`로 바꿉니다. 다른 권한은 주지 않습니다.
5. 만료 기간을 정하고 만든 뒤, 표시되는 토큰을 복사해 둡니다. 다시 볼 수 없습니다.

이 토큰으로 할 수 있는 일은 이 저장소의 자동화를 실행하거나 취소하는 것뿐입니다.

## 2. Cloudflare Worker 만들기

1. https://dash.cloudflare.com 에서 계정을 만들고 로그인합니다.
2. `Workers & Pages > Create > Worker`를 누르고, 이름을 정해 `Deploy`합니다(예: `controlpad-panel`).
3. `Edit code`를 눌러 기본 코드를 모두 지우고, 이 폴더의 `worker.js` 내용을 그대로 붙여 넣은 뒤 `Deploy`합니다.
4. Worker의 `Settings > Variables and Secrets`에서 두 값을 **Secret**으로 추가합니다.
   - `PANEL_CODE`: 평가단에게 알려 줄 코드. 길고 추측하기 어려운 값으로 직접 정합니다.
   - `GITHUB_TOKEN`: 1번에서 복사한 토큰.
5. Worker 주소(`https://이름.계정.workers.dev`)를 복사합니다. 브라우저로 열어 `Control Pad panel relay`가 보이면 정상입니다.

## 3. 앱에 주소 알려 주기

저장소의 `release/labs.json`에서 `"relay"` 값을 Worker 주소로 바꿔 커밋합니다.

```json
"relay": "https://controlpad-panel.계정.workers.dev"
```

앱은 이 파일에서 주소를 읽으므로 앱을 다시 빌드할 필요가 없습니다(반영까지 몇 분 걸릴 수 있습니다).

## 4. 평가단에게 알려 주기

평가단에게 `PANEL_CODE` 값을 따로 전달합니다. 평가단은 앱의 `설정 > 실험실 · 평가단`에서 코드를 한 번 입력하고 요청 버튼을 누르면 됩니다.

## 알아 둘 점

- **코드를 아는 사람은 누구나 요청할 수 있습니다.** 코드가 새어 나가면 Worker의 `PANEL_CODE`를 새 값으로 바꾸세요.
- **취소**: 반영되기 전에(정식 추가는 최대 30분, 숨김·복귀는 5분) GitHub 저장소의 `Actions > Panel request`에서 진행 중인 실행을 취소하면 반영되지 않습니다.
- **요청이 안 될 때**: 앱에 "저장소에 전달하지 못했습니다 (401)"이 뜨면 토큰이 만료되었거나 권한이 다른 것입니다. 1번을 다시 하고 `GITHUB_TOKEN`을 바꾸세요.
- 저장소 이름이나 브랜치를 바꾸면 Worker에 `REPO`, `BRANCH` 변수를 추가해 맞춰 줍니다.
- 토큰과 평가단 코드는 저장소나 앱에 적지 마세요. Worker의 Secret에만 둡니다.

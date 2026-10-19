## Phase 0 절차 편차 (2026-10-19)

- 스킬 절차에는 `smoke.sh`가 승인 전 Phase 0 단계로 적혀 있었지만, `smoke.sh`는 실행 승인(`preflight-approved`)이 없으면 실행을 거부한다. Phase 0 직전에 사용자 승인을 먼저 받고 스모크를 실행했다. 스킬 문서(`langbench-add-language` 5단계 (바))에 이 순서를 반영했다.
- Laravel Octane은 FrankenPHP 1.13.0 이상을 요구한다. 처음 설치한 1.12.6이 PATH에 있자 Octane은 기존 바이너리를 `.backup`으로 바꾸고 최신 바이너리를 앱 디렉터리에 내려받으려 했다. 에이전트 run 안에서 툴체인을 받는 일을 막기 위해 실행 전에 공식 릴리스 1.13.1로 교체하고, 내려받기가 일어나지 않는 것을 확인했다.
- zsh에서 `$ECR_REPO:tools`가 `:t` 수식어로 해석되어 이미지 태그가 깨졌다. `${ECR_REPO}:tools`로 고쳐 다시 push했고 스킬 문서도 고쳤다.

## PHP 채점 포트 탐지 결함과 재실행 (2026-10-19 08:25-08:55)

`sonnet55-php-1`은 30 iteration 동안 매번 게이트에서 기각되었고(호스트 0파일·13요청), 40분 상한으로 끝났다. 에이전트는 매 iteration 자체 Hurl에서 154요청 통과를 보고했다. 하네스 채점에서는 모든 파일의 첫 요청 `POST /api/users`가 404였다.

원인은 하네스다. Laravel Octane이 띄우는 FrankenPHP(Caddy)는 앱 포트(`PORT`)와 함께 관리 포트를 연다. 실행 중이던 `opus55-php-1` 서버에서 `127.0.0.1:2142`(관리)가 `*:8123`(앱)보다 먼저 `lsof`에 나열되는 것을 확인했다. `measure.sh`의 `find_port`는 리포 소속 프로세스가 LISTEN하는 첫 포트로 채점했으므로 관리 포트에 요청을 보냈다. Java·C#은 포트를 하나만 열어 영향이 없었다.

사용자 결정(08:3x)에 따라 배치를 멈추고 다음을 했다.

- `find_port`가 주입한 `PORT`에서 리포 소속 프로세스가 LISTEN하면 그 포트로 채점하도록 고쳤다. `PORT`를 무시하는 앱은 `PORT_GRACE`(15초) 뒤 기존처럼 첫 포트로 채점한다(커밋 `ee7f76b`, 테스트 2개 추가).
- 결함의 영향을 받은 `sonnet55-php-1`(40분 상한 소진)과 `opus55-php-1`(약 17분 진행)을 무효로 하고, 산출물·세션을 실험 디렉터리의 `invalid-portbug/`로 옮겼다(리포에는 metrics·로그·세션을 `runs/invalid/portbug/`에 보관). 무효 run의 사용량(API 정가 환산 약 $8.7)은 비용 누적에 남겼다.
- 검증으로, 무효 처리한 `sonnet55-php-1` 앱을 고친 `measure.sh`로 채점하면 `13,154`였다.
- 첫 재시작에서 `sonnet55-php-1`이 이전 구간 경과 시간을 이어받아 즉시 timeout으로 끝났다(R19 재개 규칙). 그 산출물도 `invalid-portbug/restart-0854/`(리포 `runs/invalid/portbug/restart-0854/`)로 옮기고, `driver.log`에 두 run의 `held:` 표시를 남겨 경과 시간을 0으로 되돌린 뒤 08:55에 다시 시작했다.

두 run은 결함이 없는 하네스로 iteration 1부터 다시 실행되었다. 결함은 PHP 전체에 같은 방식으로 작용했고 PHP run 중 하나만 골라 재실행한 것이 아니므로, 합격 쪽 선택 편향은 없다고 판단한다.

## C# 취약점 스캐너 결함 (2026-10-19 11:51-14:10)

언어별 첫 run의 Fargate 측정에서 `opus55-csharp-1`의 `dotnet.json`이 생기지 않았다. 레지스트리의 C# 스캐너는 내보낸 작업 트리의 루트에서 `dotnet list package --vulnerable`을 실행했는데, C# 앱 6개는 모두 `.csproj`를 `src/<이름>/` 아래에 두어 프로젝트 파일을 찾지 못했다. 그대로 측정하면 C# 6 run의 `vulns`가 모두 빈 칸이 된다. 에이전트 run과 채점에는 영향이 없다.

사용자 결정에 따라 스캐너를 고쳤다(커밋 `b912806`). `.sln`·`.slnx`를 먼저 찾고, 없으면 테스트 프로젝트와 `bin`·`obj` 아래를 뺀 첫 `.csproj`를 쓴다(deps 계수와 같은 규칙). 그 뒤 `dotnet restore`를 하고 `dotnet list <경로> package --vulnerable --include-transitive --format json`을 실행한다. 알려진 취약 버전(Newtonsoft.Json 12.0.1)을 하위 디렉터리 프로젝트에 넣어 GHSA-5crp-9r3c-p9vr(High) 탐지를 확인했다. 실패한 첫 측정은 `runs/invalid/scanner/`에 두고, `opus55-csharp-1`을 다시 측정한 결과를 쓴다.

## Fargate 측정 인프라 실패와 재측정 (2026-10-19 14:25-15:00)

나머지 15 run 측정에서 두 run이 측정값 없이 끝났다. `opus55-csharp-2`는 1·2회차에 ECR 이미지를 받지 못했고(`CannotPullContainerError ... dial tcp ...:443: i/o timeout`), 3회차는 Graviton4 태스크를 교체 상한 20회 안에 얻지 못했다(`cpu-unavailable`). `opus55-php-3`는 세 시도 모두 `cpu-unavailable`이었다. 두 run 모두 측정값이 하나도 없으므로, 앱과 무관한 인프라 실패로 보고 같은 사양으로 다시 측정했다(EXP-001의 Fargate 재측정과 같은 처리). 시도 기록은 `fargate-attempts.csv`에 남아 있다.

## 보관 형식

- `runs/runtime/<run>/`은 Fargate 측정 결과를 합친 디렉터리(`runtime-fg-<run>`)다. `runs_row.py`는 `runtime-<run>`을 읽으므로 실험 디렉터리에서 `runtime-<run>` → `runtime-fg-<run>` 심볼릭 링크를 만들어 집계했다.
- PHP 6 run의 `trivy.json`은 베이스 이미지(Debian 13.7) OS 패키지 취약점 2332건 때문에 각각 약 9 MB라 `trivy.json.gz`로 압축했다.
- Fargate 파일럿(EXP-001 `opus55-go-1` 앱 복사본) 결과는 `runs/invalid/pilot/`에 둔다. 집계에 쓰지 않는다.
- Java 4개 앱(`sonnet55-java-1`·`-2`, `opus55-java-2`·`-3`과 각 `-fu`)은 `application.properties`에 JWT 서명 키의 개발용 기본값(`${JWT_SECRET:<기본값>}`)을 넣었다. 실험자의 실제 비밀값은 아니지만(`redact_keys.py` 치환 0건) 커밋 전 키 검사에 걸리므로, 보관본의 기본값과 세션 로그(`runs/sessions/` 4개)의 같은 값을 `<REDACTED:JWT_SECRET>`으로 바꿨다. 측정은 원본 앱으로 끝났으므로 결과값에는 영향이 없다. 보관본을 다시 실행할 때는 `JWT_SECRET` 환경변수를 지정해야 한다.

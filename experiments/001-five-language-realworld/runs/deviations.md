## 독립 재검증 이상 (2026-10-18)

재검증(measure.sh·measure_docker.sh 각 2회, 13:53-14:13) 중 Docker 채점 2건이 기준값 `13,154`와 달랐다.

- `sonnet55-rust-3` Docker 1·2회차가 `0,0`이었다. 두 시도 모두 1초 안에 끝났으므로 빌드 또는 컨테이너 기동 단계에서 실패한 것이다. 실패 시점의 `docker-build-last.log`는 다음 run의 측정으로 덮어써져 원인을 확인하지 못했다. 같은 순서(호스트 → Docker)로 재시도한 3회는 모두 `13,154`였고 본 실행 게이트도 Docker 13/154였다. 일시적 Docker 장애로 판단하고 run은 합격으로 유지한다. 재시도 행은 측정 시각을 기록하지 못해 `time`이 비어 있다.
- `opus55-python-3` Docker 1회차가 `10,127`이었다. 2-4회차는 모두 `13,154`였다. 기동 직후 일부 요청이 실패한 일시적 현상으로 판단하고 합격으로 유지한다.

두 판단이 틀렸다면 해당 run의 Docker 재현성이 낮은 것이며, 런타임 측정 단계의 Docker 기동 결과로 다시 확인한다.

## 후속 과제 sonnet55-cpp-3-fu 실패와 하네스 결함 (2026-10-18)

`sonnet55-cpp-3-fu`는 5 iteration 모두 `rejected`(호스트 0/0)로 끝났다. 원인은 하네스다. `driver.sh`의 후속 과제 모드는 `cp -R app-<run> app-<run>-fu`로 앱을 복사하는데, 이때 원 run의 `build/CMakeCache.txt`가 함께 복사된다. 이 캐시는 원 디렉터리 경로를 기록하고 있어 `start.sh`의 `cmake -S . -B build`가 "CMakeCache.txt directory ... is different" 오류로 즉시 실패한다. 에이전트는 `/tmp/fu-build`에 별도 빌드해 자체 Hurl(165요청)을 통과시켰지만 앱 디렉터리의 `build/`는 고치지 않았다.

같은 복사 방식은 C++ 후속 과제 6건 모두에 적용됐다. 이 중 3건(sonnet55-cpp-1, opus55-cpp-1, opus55-cpp-3)은 세션에서 같은 오류를 만나 직접 해결했고, 2건(sonnet55-cpp-2, opus55-cpp-2)은 오류 없이 빌드 디렉터리를 다시 만들었다. 조건이 모든 run에 같았으므로 사전 등록 규칙대로 이 run은 실패로 유지한다. 실패한 run만 재실행하면 합격 쪽으로 편향되기 때문이다. 감도 확인으로, 앱 사본에서 `build/`만 지우고 `measure.sh`(14파일)를 돌리면 `14,167`로 통과했다. 즉 코드 자체는 과제를 만족하며, 실패는 기동 환경 정리 실패다. 후속 실험에서는 복사 시 빌드 산출물을 제외하도록 하네스를 고친다.

## 런타임 측정 환경 변경: 로컬 Docker → AWS Fargate Spot (2026-10-18)

사전 등록 설계는 런타임 측정을 실험 머신의 로컬 Docker(`--cpus=2 --memory=1g`)에서 run 하나씩 순차로 하도록 정했다. 로컬 순차 측정은 run당 약 13분, 30 run에 약 6.5시간이 걸린다. 16:07에 실험자(사용자)가 Fargate Spot으로 전면 재측정하고 이미 끝난 로컬 측정은 비교용으로 남기라고 결정했다. 로컬 측정은 `opus55-python-1`(완료)과 `sonnet55-go-1`(진행 중이던 것을 끝까지 측정, govulncheck 실패 포함) 두 건이 `runtime-<run>/`에 남아 있으며, 비교 자료로만 쓴다.

Fargate 측정은 `fargate/` 아래 스크립트로 한다. 빌드 시간·이미지 크기·정적 분석(scc·trivy·언어 스캐너)은 측정 환경과 무관하거나 로컬 빌드 시간이 기준이므로 로컬에서 run 하나씩 순차로 잰다(`local_prep.sh`, runtime.sh의 해당 부분을 그대로 옮김). 이미지는 Mac(arm64)에서 빌드해 ECR(us-east-1, 계정 roboco)에 올린다. 콜드 스타트·메모리·시드·k6 부하 3회·soak은 Fargate Spot ARM64에서 run마다 두 태스크로 잰다. 앱 태스크는 2 vCPU(태스크 하드 제한)·앱 컨테이너 메모리 1 GiB 하드 제한이고 probe 사이드카가 콜드 스타트와 메모리를 잰다. 부하 태스크는 4 vCPU·8 GiB에서 k6와 시드를 돌린다. 두 태스크는 같은 서브넷에 둔다. 다른 run과 동시에 돌아도 태스크마다 자원이 분리되므로 서로 간섭하지 않는다.

로컬과 정의가 다른 점은 다음과 같다. 콜드 스타트는 로컬이 `docker run` 직전부터, Fargate는 앱 컨테이너 StartedAt(이미지 pull 이후)부터 첫 `GET /api/tags` 200까지다. 메모리는 둘 다 usage - inactive_file(MiB)이다. 컨테이너가 보는 CPU 수가 로컬은 Mac 전체 코어, Fargate는 2개이므로 CPU 수로 워커 수를 정하는 앱은 Fargate에서 워커가 적고 메모리가 낮게 나온다(파일럿: opus55-python-1 유휴 메모리 로컬 369 MiB, Fargate 144 MiB). 부하는 로컬이 호스트→Docker 포트 포워딩, Fargate가 같은 서브넷의 다른 태스크에서 VPC 네트워크로 온다. Docker의 swap 허용과 달리 Fargate에는 swap이 없다.

Fargate는 태스크가 놓일 CPU 세대를 보장하지 않는다. 16:2x에 시험 태스크 15개를 띄우자 Graviton4(`CPU part 0xd4f`) 10, Graviton2(`0xd0c`) 3, Graviton3(`0xd40`) 2개였다. 세대가 섞이면 언어 간 차이에 하드웨어 차이가 섞이므로, 측정 시작 전에 앱·부하 태스크가 모두 Graviton4인지 확인하고 아니면 측정 없이 태스크를 새로 띄운다(측정 시도로 세지 않음, run당 교체 상한 20). 이 규칙은 측정값이 아니라 환경만 보고 적용한다. Spot 회수·기동 실패는 새 태스크로 다시 재며 run당 최대 3회다. 시도 기록은 `fargate-attempts.csv`, 진행 기록은 `fargate.log`다.

## 툴체인 수정: govulncheck 재빌드 (2026-10-18 16:17)

로컬 런타임 측정의 `sonnet55-go-1`에서 govulncheck가 "Go 버전 불일치로 패키지 로드 실패"로 종료 코드 1을 냈다. 설치된 govulncheck v1.9.0이 go1.26.9로 빌드돼 있었고 PATH의 Go는 1.27.2였다. Phase 0의 툴체인 점검은 `-version` 출력만 확인해 이를 잡지 못했다. 같은 v1.9.0을 go1.27.2로 다시 빌드한 뒤(`go install golang.org/x/vuln/cmd/govulncheck@v1.9.0`) 같은 앱에서 종료 코드 0을 확인했다. Fargate 측정의 정적 분석은 모두 재빌드한 govulncheck로 돈다.

## Fargate 측정 재실행 (2026-10-18 16:40, 17:10)

실험 머신의 DNS 조회가 간헐적으로 실패해 Fargate 측정 6 run이 앱과 무관한 이유로 끝났다. 16:40 무렵 로컬 준비 3건(`opus55-python-2` ECR push, `opus55-go-2`·`opus55-rust-2` 의존성 내려받기 중 빌드)이 실패했고, 16:39·17:10에 AWS API 연결 실패로 오케스트레이터 스레드 3건(`opus55-typescript-1`, `sonnet55-typescript-3`, `opus55-go-3`)이 중단됐다. 남은 태스크 4개를 중지하고, AWS 호출에 연결 오류 재시도(최대 30회)를 넣은 뒤 6 run을 로컬 준비부터 다시 측정했다(S3 접두어 `main2`). 실패한 시도의 산출물은 `fargate/failed-attempt-0/`에 남겼다. 재측정에서 6 run 모두 정상 종료했다. 재측정 중 `opus55-python-2` 시도 1은 도구 이미지 pull 시간 초과(Fargate 쪽 네트워크)로 기동하지 못해 시도 2로 측정했다. 재측정 대상은 실패 원인(네트워크)으로 정했고 측정값을 보고 고르지 않았다.

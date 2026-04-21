# 재난대응 서비스 — RDS 설계 문서

| 항목 | 내용 |
|---|---|
| 문서 버전 | v7.0 |
| 작성일 | 2026-04-19 |
| 작성자 | RDS 팀 |
| DB 엔진 | PostgreSQL 16+ |
| 정규화 수준 | 제3정규형 (3NF) |
| 캐시 | Redis |
| 변경 기준 | external-ingestion RDS 설계 문서 v2 (민중) 패치 반영 |

---

## 목차

1. [설계 개요 및 원칙](#1-설계-개요-및-원칙)
2. [테이블 명세서](#2-테이블-명세서)
3. [인덱스 및 FK 관계 정의](#3-인덱스-및-fk-관계-정의)
4. [Redis 캐시 운영표](#4-redis-캐시-운영표)
5. [제3정규형(3NF) 검증](#5-제3정규형3nf-검증)
6. [ERD 관계 및 데이터 흐름](#6-erd-관계-및-데이터-흐름)

---

## 1. 설계 개요 및 원칙

### 1.1 설계 목표

재난대응 서비스의 RDS(PostgreSQL)를 제3정규형(3NF) 기준으로 설계하며, DDL 작성 직전 수준의 명세를 제공하는 문서.

### 1.2 설계 원칙

| 원칙 | 내용 |
|---|---|
| 책임 분리 우선 | 컬럼 추가보다 엔티티 분리를 우선 — 단일 책임 원칙(SRP) 준수 |
| 계산값 비저장 | 현재인원 등 유도 가능한 값은 DB에 저장하지 않고 쿼리/캐시로 처리 |
| 비회원 대응 | `user_id` nullable 유지 — `visitor_name` / `visitor_phone`으로 비회원 입소 처리 |
| 재난 3종 한정 | 지진(EARTHQUAKE) / 홍수(FLOOD) / 산사태(LANDSLIDE) 3종만 지원. `disaster_type` CHECK 제약으로 강제 |
| 암호화 전제 | `rrn_front_6`는 AES-256 암호화 후 저장 (애플리케이션 레이어 처리) |
| 감사 추적 | `admin_audit_log` + `evacuation_event_history` 이중 감사 구조 |
| 캐시 분리 | Redis는 파생 데이터만 저장 — 원천 데이터는 반드시 RDS |
| 보조 기능 분리 | 날씨·대기질은 재난 판단용이 아닌 사용자 편의 제공용. 핵심 재난 기능과 장애 영향 분리 |

### 1.3 엔티티 목적 요약

| 테이블명 | 역할 |
|---|---|
| `app_user` | 서비스 회원 및 관리자 계정 정보를 저장하는 중심 엔티티 |
| `shelter` | 대피소 유형·재난 유형·운영 상태·담당자 정보를 포함한 공공 대피소 마스터 테이블 |
| `disaster_alert` | 지진·홍수·산사태 3종 재난의 공공 API 발령·해제 이력을 영구 보관 |
| `disaster_alert_detail` | 재난 상세 정보 저장 테이블. `disaster_alert`와 1:1 관계이며 지진 규모·진앙·진도 및 유형별 상세 JSON을 저장 |
| `evacuation_entry` | 개별 입소자의 현재 상태를 추적 (현재인원 COUNT의 기준 테이블) |
| `entry_detail` | 입소자의 가족관계·건강상태·특별관리 여부 등 상세 정보 (`evacuation_entry`와 1:1) |
| `evacuation_event_history` | 입소·퇴소·이송 등 상태 변경 이벤트의 불변 감사 로그 |
| `admin_audit_log` | 관리자가 수행한 모든 데이터 변경 작업의 법적 감사 기록 |
| `weather_log` | 기상청 단기예보 API 수집 이력 (격자 좌표 기준, 보조 기능) |
| `air_quality_log` | 에어코리아 대기오염정보 API 수집 이력 (측정소 기준, 보조 기능) |
| `external_api_source` | 외부 API 소스 메타 정의 테이블 |
| `external_api_schedule` | 그룹 A-1 polling loop 주기 설정 참조 테이블 |
| `external_api_execution_log` | 외부 API 수집 실행 이력 및 실패 이력 테이블 |
| `external_api_raw_payload` | 외부 API 원본 응답 보관 테이블 (90일 보존) |
| `external_api_normalization_error` | 정규화 실패 추적 테이블 |

### 1.4 API ↔ 테이블 컬럼 매핑

`POST /admin/evacuation-entries` 기준 필드 매핑.

| API 필드 | 매핑 테이블 | 매핑 컬럼 | 비고 |
|---|---|---|---|
| `address` | `evacuation_entry` | `address` | 입소자 주소. 입소 기본 정보이므로 entry에 위치 |
| `familyInfo` | `entry_detail` | `family_info` | 가족관계 자유 입력 텍스트 |
| `healthStatus` | `entry_detail` | `health_status` | CHECK IN ('정상','부상','응급','기타') |
| `specialProtectionFlag` | `entry_detail` | `special_protection_flag` | 특별관리대상 여부 boolean |

추가 매핑 규칙:

| API 필드 | 매핑 테이블 | 매핑 컬럼 | 비고 |
|---|---|---|---|
| `shelterName` | `shelter` | `name` | 응답 전용 필드명 변환 |
| `capacityTotal` | `shelter` | `capacity` | 응답/수정 DTO에서 사용하는 이름 |
| `phoneNumber` | `app_user` | `phone` | `GET /me` 응답 기준 |
| `phoneNumber` | `evacuation_entry` | `visitor_phone` | 비회원 입소자 연락처 |
| `name` | `evacuation_entry` | `visitor_name` | 비회원 입소자 이름 |
| `currentOccupancy` | 계산값 | — | `evacuation_entry` COUNT 기반 파생값 |
| `availableCapacity` | 계산값 | — | `shelter.capacity - currentOccupancy` |
| `details` | `disaster_alert_detail` | 상세 컬럼 조합 | `GET /disasters/{type}/latest` 응답 구성용 |

### 1.5 현재인원 계산 방식

```sql
SELECT COUNT(*) FROM evacuation_entry
WHERE shelter_id = :id AND entry_status = 'ENTERED';
```

- 현재인원은 `evacuation_entry`의 `entry_status = 'ENTERED'` 레코드 수로 실시간 계산
- 별도 컬럼에 저장하지 않음 → 중복 저장 방지 / 3NF 준수 / 정합성 보장
- 사용자 조회: `shelter:status:{id}` (Redis TTL **30초**) — 재난 상황 즉각 대응 목적
- 관리자 조회: `admin:shelter:status:{id}` (Redis TTL **5분**) — 운영 판단은 약간의 지연 허용
- 입소·퇴소·이송 이벤트 발생 시 두 키 모두 즉시 DEL (Cache-Aside 패턴)

---

## 2. 테이블 명세서

### 2.1 app_user

서비스 회원 및 관리자 계정 정보를 저장하는 중심 엔티티. `role` 컬럼으로 일반 사용자와 관리자를 구분.

| 컬럼명 | 데이터 타입 | KEY | NULL | Default | 설명 |
|---|---|---|---|---|---|
| `user_id` | BIGSERIAL | PK | NO | — | 사용자 고유 식별자 (자동 증가) |
| `username` | VARCHAR(50) | UQ | NO | — | 로그인 ID (중복 불가) |
| `password_hash` | VARCHAR(255) | — | NO | — | BCrypt 암호화된 비밀번호 |
| `name` | VARCHAR(50) | — | NO | — | 실명 |
| `rrn_front_6` | VARCHAR(255) | — | YES | NULL | 주민번호 앞 6자리 (AES-256 암호화) |
| `phone` | VARCHAR(20) | — | YES | NULL | 연락처 |
| `role` | VARCHAR(10) | CHK | NO | 'USER' | CHECK IN ('USER','ADMIN') |
| `is_active` | BOOLEAN | — | NO | TRUE | 계정 활성 여부 |
| `created_at` | TIMESTAMPTZ | — | NO | CURRENT_TIMESTAMP | 계정 생성 시각 |
| `updated_at` | TIMESTAMPTZ | — | NO | CURRENT_TIMESTAMP | 최종 수정 시각 |

> - `rrn_front_6`는 서비스 레이어에서 AES-256으로 암호화 후 저장. 복호화도 서비스 레이어에서만 수행.
> - `user_profile` 분리 여부 → 현 단계에서는 단일 테이블 유지. 프로필 확장 시 1:1 분리 적용.

---

### 2.2 shelter

대피소 유형·재난 유형·운영 상태·담당자 정보를 포함한 대피소 마스터 테이블. 서울시 공공데이터 기반으로 초기 적재.

| 컬럼명 | 데이터 타입 | KEY | NULL | Default | 설명 |
|---|---|---|---|---|---|
| `shelter_id` | BIGSERIAL | PK | NO | — | 대피소 고유 ID (자동 증가) |
| `name` | VARCHAR(100) | — | NO | — | 대피소 명칭 |
| `shelter_type` | VARCHAR(50) | CHK/IDX | NO | — | 지도 레이어 기준 유형 |
| `disaster_type` | VARCHAR(20) | CHK/IDX | NO | — | CHECK IN ('EARTHQUAKE','LANDSLIDE','FLOOD') |
| `address` | VARCHAR(255) | — | NO | — | 도로명 주소 |
| `latitude` | DECIMAL(10,7) | — | NO | — | 위도 |
| `longitude` | DECIMAL(10,7) | — | NO | — | 경도 |
| `capacity` | INT | — | NO | — | 최대 수용 인원 |
| `manager` | VARCHAR(50) | — | YES | NULL | 담당자명 |
| `contact` | VARCHAR(50) | — | YES | NULL | 연락처 |
| `shelter_status` | VARCHAR(20) | CHK | NO | '운영중' | CHECK IN ('운영중','운영중단','준비중') |
| `note` | TEXT | — | YES | NULL | 비고 / 특이사항 |
| `created_at` | TIMESTAMPTZ | — | NO | CURRENT_TIMESTAMP | 등록 일시 |
| `updated_at` | TIMESTAMPTZ | — | NO | CURRENT_TIMESTAMP | 최종 수정 일시 |

#### 재난 유형 ↔ 대피소 유형 매핑

| disaster_type | shelter_type 값 | 비고 |
|---|---|---|
| EARTHQUAKE (지진) | 민방위대피소, 지진옥외대피장소, 이재민임시주거시설(지진겸용), 이재민임시주거시설 | 서울시 지진대피소 공공데이터 기반 |
| FLOOD (홍수) | 수해대피소 | 서울시 수해대피소 공공데이터 기반 |
| LANDSLIDE (산사태) | 산사태임시대피소 | 서울시 산사태 임시대피소 공공데이터 기반 |

> - 외부 대피소 마스터 수집은 `name`, `shelter_type`, `disaster_type`, `address`, `latitude`, `longitude`, `capacity` 컬럼만 갱신할 수 있다.
> - `manager`, `contact`, `shelter_status`, `note`는 내부 관리자 운영 컬럼으로 외부 수집이 덮어쓰지 않는다.

---

### 2.3 disaster_alert

지진·홍수·산사태 3종 재난의 공공 API 발령·해제 이력을 영구 보관.

| 컬럼명 | 데이터 타입 | KEY | NULL | Default | 설명 |
|---|---|---|---|---|---|
| `alert_id` | BIGSERIAL | PK | NO | — | 재난 알림 고유 ID (자동 증가) |
| `disaster_type` | VARCHAR(20) | CHK | NO | — | CHECK IN ('EARTHQUAKE','LANDSLIDE','FLOOD') |
| `region` | VARCHAR(100) | IDX | NO | — | 발생 지역 (시/구 단위) |
| `level` | VARCHAR(10) | CHK | NO | — | CHECK IN ('관심','주의','경계','심각') |
| `message` | TEXT | — | NO | — | 재난문자 원문 |
| `source` | VARCHAR(50) | — | NO | — | 출처 API 식별자 (예: KMA_EARTHQUAKE) |
| `issued_at` | TIMESTAMPTZ | IDX | NO | — | 재난 발령 시각 |
| `expired_at` | TIMESTAMPTZ | — | YES | NULL | 재난 해제 시각 (NULL = 현재 활성 재난) |
| `created_at` | TIMESTAMPTZ | — | NO | CURRENT_TIMESTAMP | DB 저장 시각 |

> - `expired_at = NULL` → 현재 활성 재난. Ingestion Pod가 해제 감지 시 `expired_at` 업데이트.
> - `source` 컬럼 운용값: `SAFETY_DATA_ALERT`, `KMA_EARTHQUAKE`, `SEOUL_EARTHQUAKE`, `FORESTRY_LANDSLIDE`, `SEOUL_RIVER_LEVEL`

---

### 2.4 disaster_alert_detail

재난 상세 구조 분리 테이블. `disaster_alert`와 1:1 관계. `GET /disasters/{disasterType}/latest` 응답의 `details` 객체 구성에 사용.

| 컬럼명 | 데이터 타입 | KEY | NULL | Default | 설명 |
|---|---|---|---|---|---|
| `detail_id` | BIGSERIAL | PK | NO | — | 상세 ID |
| `alert_id` | BIGINT | FK/UQ | NO | — | `disaster_alert.alert_id` |
| `detail_type` | VARCHAR(30) | IDX | NO | — | `EARTHQUAKE` / `FLOOD` / `LANDSLIDE` |
| `magnitude` | DECIMAL(4,1) | — | YES | NULL | 지진 규모 |
| `epicenter` | VARCHAR(255) | — | YES | NULL | 진앙 정보 |
| `intensity` | VARCHAR(20) | — | YES | NULL | 진도 |
| `detail_json` | JSONB | — | YES | NULL | 유형별 확장 상세 정보 |
| `created_at` | TIMESTAMPTZ | — | NO | CURRENT_TIMESTAMP | 생성 시각 |
| `updated_at` | TIMESTAMPTZ | — | NO | CURRENT_TIMESTAMP | 수정 시각 |

> - `disaster_alert`와 1:1 관계. ON DELETE CASCADE.
> - 지진은 `magnitude`, `epicenter`, `intensity`를 우선 사용.
> - 홍수·산사태 등 유형별 확장 정보는 `detail_json`에 저장.

`detail_json` 활용 예시:
```json
// FLOOD
{ "river_level": 4.32, "danger_level": "주의", "station": "한강대교" }

// LANDSLIDE
{ "risk_grade": "3등급", "predicted_area": "관악구 신림동" }
```

---

### 2.5 evacuation_entry

개별 입소자의 현재 상태를 추적하는 핵심 트랜잭션 테이블. 현재인원 COUNT의 기준.

| 컬럼명 | 데이터 타입 | KEY | NULL | Default | 설명 |
|---|---|---|---|---|---|
| `entry_id` | BIGSERIAL | PK | NO | — | 입소 기록 고유 ID (자동 증가) |
| `shelter_id` | BIGINT | FK/IDX | NO | — | 대피소 ID (shelter.shelter_id) |
| `alert_id` | BIGINT | FK | YES | NULL | 연관 재난 알림 ID |
| `user_id` | BIGINT | FK | YES | NULL | 회원 ID (비회원 = NULL) |
| `visitor_name` | VARCHAR(50) | — | YES | NULL | 비회원 입소자 이름 |
| `visitor_phone` | VARCHAR(20) | — | YES | NULL | 비회원 연락처 |
| `address` | VARCHAR(255) | — | YES | NULL | 입소자 주소 |
| `entry_status` | VARCHAR(15) | CHK/IDX | NO | 'ENTERED' | CHECK IN ('ENTERED','EXITED','TRANSFERRED') |
| `entered_at` | TIMESTAMPTZ | — | NO | CURRENT_TIMESTAMP | 입소 시각 |
| `exited_at` | TIMESTAMPTZ | — | YES | NULL | 퇴소 시각 |
| `note` | TEXT | — | YES | NULL | 특이사항 메모 |

> - `user_id = NULL` → 비회원 입소. `visitor_name + visitor_phone`으로 식별.
> - `entry_status = TRANSFERRED` → 다른 대피소로 이송 완료 상태.
> - 가족관계·건강상태·특별관리 여부는 `entry_detail` 테이블(2.6)로 분리.

---

### 2.6 entry_detail

입소자의 가족관계·건강상태·특별관리 여부 등 상세 정보. `evacuation_entry`와 1:1 관계.

| 컬럼명 | 데이터 타입 | KEY | NULL | Default | API 필드 | 설명 |
|---|---|---|---|---|---|---|
| `detail_id` | BIGSERIAL | PK | NO | — | — | 상세 기록 고유 ID (자동 증가) |
| `entry_id` | BIGINT | FK/UQ | NO | — | — | evacuation_entry.entry_id (1:1, UNIQUE) |
| `family_info` | TEXT | — | YES | NULL | `familyInfo` | 가족 구성 자유 입력 (예: 영아 1명 동반) |
| `health_status` | VARCHAR(20) | CHK/IDX | NO | '정상' | `healthStatus` | CHECK IN ('정상','부상','응급','기타') |
| `health_note` | TEXT | — | YES | NULL | — | 건강 상태 상세 메모 (예: 당뇨, 휠체어 필요, 복용 약물 등) |
| `special_protection_flag` | BOOLEAN | IDX | NO | FALSE | `specialProtectionFlag` | 특별관리대상 여부 (노인·장애인·임산부 등) |
| `support_note` | TEXT | — | YES | NULL | — | 특별관리 필요 사항 상세 |
| `created_at` | TIMESTAMPTZ | — | NO | CURRENT_TIMESTAMP | — | 기록 생성 시각 |
| `updated_at` | TIMESTAMPTZ | — | NO | CURRENT_TIMESTAMP | — | 최종 수정 시각 |

> - `health_status`는 상태 분류값. 자유서술형 건강 정보는 `health_note`에 저장.
> - API의 `healthStatus`는 구현 시 enum 분류값(`정상`,`부상`,`응급`,`기타`)으로 제한.
> - `entry_id`에 UNIQUE 제약 → `evacuation_entry`와 완전한 1:1 관계 보장.
> - ON DELETE CASCADE — `evacuation_entry` 삭제 시 함께 삭제.

---

### 2.7 evacuation_event_history

입소·퇴소·이송 등 상태 변경 이벤트를 append-only 방식으로 기록하는 불변 감사 테이블. 레코드 수정·삭제 금지.

| 컬럼명 | 데이터 타입 | KEY | NULL | Default | 설명 |
|---|---|---|---|---|---|
| `history_id` | BIGSERIAL | PK | NO | — | 이력 고유 ID (자동 증가) |
| `entry_id` | BIGINT | FK | NO | — | 입소 기록 ID |
| `shelter_id` | BIGINT | FK | NO | — | 대피소 ID |
| `event_type` | VARCHAR(20) | CHK | NO | — | CHECK IN ('CHECK_IN','CHECK_OUT','TRANSFER','STATUS_UPDATE') |
| `prev_status` | VARCHAR(30) | — | YES | NULL | 변경 전 상태값 |
| `next_status` | VARCHAR(30) | — | NO | — | 변경 후 상태값 |
| `recorded_by` | BIGINT | FK | YES | NULL | 처리 관리자 user_id |
| `recorded_at` | TIMESTAMPTZ | IDX | NO | CURRENT_TIMESTAMP | 이벤트 발생 시각 |
| `remark` | TEXT | — | YES | NULL | 비고 |

> - 레코드 수정·삭제 금지 — 이벤트 소싱 패턴 적용.
> - `recorded_by = NULL` 가능 → 시스템 자동 처리 또는 비회원 관련 이벤트.

---

### 2.8 admin_audit_log

관리자가 수행한 모든 데이터 변경 작업을 기록하는 법적 감사 테이블. append-only.

| 컬럼명 | 데이터 타입 | KEY | NULL | Default | 설명 |
|---|---|---|---|---|---|
| `log_id` | BIGSERIAL | PK | NO | — | 로그 고유 ID (자동 증가) |
| `admin_id` | BIGINT | FK/IDX | NO | — | 수행 관리자 user_id |
| `action` | VARCHAR(100) | — | NO | — | 수행 작업 (예: SHELTER_UPDATE, ENTRY_FORCE_EXIT) |
| `target_type` | VARCHAR(50) | — | YES | NULL | 대상 엔티티 유형 |
| `target_id` | BIGINT | — | YES | NULL | 대상 레코드 ID |
| `payload_before` | JSONB | — | YES | NULL | 변경 전 데이터 스냅샷 |
| `payload_after` | JSONB | — | YES | NULL | 변경 후 데이터 스냅샷 |
| `ip_address` | VARCHAR(45) | — | YES | NULL | 요청 IP (IPv6 포함) |
| `created_at` | TIMESTAMPTZ | IDX | NO | CURRENT_TIMESTAMP | 로그 기록 시각 |

> - `payload_before / payload_after`는 JSONB 스냅샷 — 집계·조회용이 아닌 변경 증거 보존 목적.
> - 관리자 API의 `reason` 필드는 별도 컬럼 없이 `payload_after` 내 감사 메타데이터로 저장. 예: `{ ..., "auditMeta": { "reason": "현장 재점검" } }`
> - `admin_id` FK는 ON DELETE RESTRICT — 감사 로그 보존 의무상 관리자 계정 삭제 전 로그 이관 필수.

---

### 2.9 weather_log

기상청 단기예보 API 수집 이력. 격자 좌표(nx, ny) 기준으로 저장. 보조 기능으로 핵심 재난 기능과 장애 영향 분리.

| 컬럼명 | 데이터 타입 | KEY | NULL | Default | 설명 |
|---|---|---|---|---|---|
| `log_id` | BIGSERIAL | PK | NO | — | 수집 기록 고유 ID (자동 증가) |
| `nx` | INT | IDX | NO | — | 기상청 격자 X 좌표 |
| `ny` | INT | IDX | NO | — | 기상청 격자 Y 좌표 |
| `base_date` | DATE | IDX | NO | — | 예보 기준 날짜 (YYYYMMDD) |
| `base_time` | VARCHAR(4) | IDX | NO | — | 예보 기준 시각 (HHMM, 예: 0500) |
| `forecast_dt` | TIMESTAMPTZ | IDX | NO | — | 예보 대상 일시 |
| `tmp` | DECIMAL(4,1) | — | YES | NULL | 기온 (°C) |
| `sky` | VARCHAR(10) | — | YES | NULL | 하늘 상태 (맑음/구름조금/흐림) |
| `pty` | VARCHAR(10) | — | YES | NULL | 강수 형태 (없음/비/눈/빗눈) |
| `pop` | INT | — | YES | NULL | 강수 확률 (%) |
| `pcp` | VARCHAR(20) | — | YES | NULL | 1시간 강수량 (mm, 강수없음 포함 문자열) |
| `wsd` | DECIMAL(4,1) | — | YES | NULL | 풍속 (m/s) |
| `reh` | INT | — | YES | NULL | 습도 (%) |
| `collected_at` | TIMESTAMPTZ | — | NO | CURRENT_TIMESTAMP | API 수집 시각 |

> - `(nx, ny, base_date, base_time, forecast_dt)` 복합 UNIQUE 제약 적용 — 중복 적재 방지.
> - `pcp`는 기상청 API가 "강수없음", "1mm 미만" 등 문자열로 반환하므로 VARCHAR 사용.
> - Redis `env:weather:{nx}:{ny}` 캐시 miss 시 이 테이블에서 가장 최근 레코드로 fallback.
> - 데이터 보존 기간 정책 필요 — 30일 이상 된 레코드 주기적 삭제 권장.

---

### 2.10 air_quality_log

에어코리아 대기오염정보 API 수집 이력. 측정소 기준으로 저장. 보조 기능으로 핵심 재난 기능과 장애 영향 분리.

| 컬럼명 | 데이터 타입 | KEY | NULL | Default | 설명 |
|---|---|---|---|---|---|
| `log_id` | BIGSERIAL | PK | NO | — | 수집 기록 고유 ID (자동 증가) |
| `station_name` | VARCHAR(50) | IDX | NO | — | 측정소명 (예: 강남구) |
| `measured_at` | TIMESTAMPTZ | IDX | NO | — | 측정 시각 |
| `pm10` | INT | — | YES | NULL | 미세먼지 PM10 (㎍/㎥) |
| `pm10_grade` | VARCHAR(10) | — | YES | NULL | PM10 등급 (좋음/보통/나쁨/매우나쁨) |
| `pm25` | INT | — | YES | NULL | 초미세먼지 PM2.5 (㎍/㎥) |
| `pm25_grade` | VARCHAR(10) | — | YES | NULL | PM2.5 등급 (좋음/보통/나쁨/매우나쁨) |
| `o3` | DECIMAL(4,3) | — | YES | NULL | 오존 (ppm) |
| `o3_grade` | VARCHAR(10) | — | YES | NULL | 오존 등급 (좋음/보통/나쁨/매우나쁨) |
| `khai_value` | INT | — | YES | NULL | 통합대기환경지수 (CAI) |
| `khai_grade` | VARCHAR(10) | — | YES | NULL | 통합대기환경 등급 (좋음/보통/나쁨/매우나쁨) |
| `collected_at` | TIMESTAMPTZ | — | NO | CURRENT_TIMESTAMP | API 수집 시각 |

> - `(station_name, measured_at)` 복합 UNIQUE 제약 적용 — 중복 적재 방지.
> - `khai_grade`는 사용자에게 최종 대기질 상태로 표시하는 대표 지표.
> - Redis `env:air:{station_name}` 캐시 miss 시 이 테이블에서 가장 최근 레코드로 fallback.
> - 데이터 보존 기간 정책 필요 — 30일 이상 된 레코드 주기적 삭제 권장.

---

### 2.11 external_api_source

외부 API 소스 메타 정의 테이블.

| 컬럼명 | 데이터 타입 | KEY | NULL | Default | 설명 |
|---|---|---|---|---|---|
| `source_id` | BIGSERIAL | PK | NO | — | 외부 API 소스 고유 ID |
| `source_code` | VARCHAR(50) | UQ | NO | — | 내부 식별 코드 |
| `source_name` | VARCHAR(100) | — | NO | — | API 명칭 |
| `provider` | VARCHAR(100) | — | NO | — | 제공 기관명 |
| `category` | VARCHAR(30) | IDX | NO | — | `DISASTER` / `ENVIRONMENT` / `SHELTER` |
| `auth_type` | VARCHAR(30) | — | NO | — | `API_KEY` / `FILE` / `NONE` |
| `base_url` | TEXT | — | YES | NULL | 기본 엔드포인트 (파일 형태인 경우 NULL) |
| `is_active` | BOOLEAN | — | NO | TRUE | 사용 여부 |
| `created_at` | TIMESTAMPTZ | — | NO | CURRENT_TIMESTAMP | 생성 시각 |
| `updated_at` | TIMESTAMPTZ | — | NO | CURRENT_TIMESTAMP | 수정 시각 |

초기 데이터:

| source_code | source_name | provider | category | is_active | 비고 |
|---|---|---|---|---|---|
| `SAFETY_DATA_ALERT` | 재난문자 | 행정안전부 | DISASTER | TRUE | |
| `KMA_EARTHQUAKE` | 지진 정보 | 기상청 | DISASTER | TRUE | |
| `SEOUL_EARTHQUAKE` | 서울시 지진 발생 현황 | 서울시 | DISASTER | TRUE | |
| `FORESTRY_LANDSLIDE` | 산사태 위험 예측 | 산림청 | DISASTER | FALSE | 인증키 승인 대기 |
| `SEOUL_RIVER_LEVEL` | 하천 수위 | 서울시 | DISASTER | TRUE | |
| `KMA_WEATHER` | 날씨 단기예보 | 기상청 | ENVIRONMENT | TRUE | |
| `AIR_KOREA_AIR_QUALITY` | 대기질 | 에어코리아 | ENVIRONMENT | TRUE | |
| `SEOUL_SHELTER_EARTHQUAKE` | 서울시 지진옥외대피소 | 서울시 | SHELTER | TRUE | |
| `SEOUL_SHELTER_LANDSLIDE` | 서울시 산사태 대피소 | 서울시 | SHELTER | TRUE | |
| `SEOUL_SHELTER_FLOOD` | 서울시 수해 대피소 | 서울시 | SHELTER | TRUE | xlsx 파일 형태 |

---

### 2.12 external_api_schedule

그룹 A-1 polling loop 주기 설정 참조 테이블. 그룹 A-2 / 그룹 B CronJob 주기는 Kubernetes 매니페스트로 직접 관리.

| 컬럼명 | 데이터 타입 | KEY | NULL | Default | 설명 |
|---|---|---|---|---|---|
| `schedule_id` | BIGSERIAL | PK | NO | — | 스케줄 고유 ID |
| `source_id` | BIGINT | FK/IDX | NO | — | `external_api_source.source_id` |
| `schedule_name` | VARCHAR(100) | — | NO | — | 스케줄 이름 |
| `cron_expr` | VARCHAR(100) | — | YES | NULL | cron 표현식 |
| `timezone` | VARCHAR(50) | — | NO | 'Asia/Seoul' | 스케줄 기준 타임존 |
| `request_params_json` | JSONB | — | YES | NULL | 기본 요청 파라미터 |
| `is_enabled` | BOOLEAN | — | NO | TRUE | 활성 여부 |
| `next_scheduled_at` | TIMESTAMPTZ | IDX | YES | NULL | 다음 예약 시각 |
| `created_at` | TIMESTAMPTZ | — | NO | CURRENT_TIMESTAMP | 생성 시각 |
| `updated_at` | TIMESTAMPTZ | — | NO | CURRENT_TIMESTAMP | 수정 시각 |

---

### 2.13 external_api_execution_log

외부 API 수집 실행 이력 테이블.

| 컬럼명 | 데이터 타입 | KEY | NULL | Default | 설명 |
|---|---|---|---|---|---|
| `execution_id` | BIGSERIAL | PK | NO | — | 실행 고유 ID |
| `schedule_id` | BIGINT | FK/IDX | YES | NULL | 스케줄 참조 (배치 실행 시 NULL 가능) |
| `source_id` | BIGINT | FK/IDX | NO | — | 외부 API 참조 |
| `execution_status` | VARCHAR(20) | IDX | NO | — | `RUNNING` / `SUCCESS` / `FAILED` / `PARTIAL_SUCCESS` |
| `started_at` | TIMESTAMPTZ | IDX | NO | — | 실행 시작 시각 |
| `ended_at` | TIMESTAMPTZ | — | YES | NULL | 실행 종료 시각 |
| `http_status` | INT | — | YES | NULL | 대표 응답 코드 |
| `retry_count` | INT | — | NO | 0 | 재시도 횟수 |
| `records_fetched` | INT | — | NO | 0 | 수집 건수 |
| `records_normalized` | INT | — | NO | 0 | 정규화 성공 건수 |
| `records_failed` | INT | — | NO | 0 | 정규화 실패 건수 |
| `error_code` | VARCHAR(100) | — | YES | NULL | 내부 오류 코드 |
| `error_message` | TEXT | — | YES | NULL | 오류 요약 |
| `trace_id` | VARCHAR(100) | — | YES | NULL | 추적 ID |
| `created_at` | TIMESTAMPTZ | — | NO | CURRENT_TIMESTAMP | 기록 생성 시각 |

> - `schedule_id` NULL 허용 — `SEOUL_SHELTER_FLOOD`처럼 배치 스크립트로만 실행되는 항목도 동일 테이블 관리.
> - 보존 기간: 30일 이상 권장.

---

### 2.14 external_api_raw_payload

외부 API 원본 응답 보관 테이블.

| 컬럼명 | 데이터 타입 | KEY | NULL | Default | 설명 |
|---|---|---|---|---|---|
| `raw_id` | BIGSERIAL | PK | NO | — | 원본 payload ID |
| `execution_id` | BIGINT | FK/IDX | NO | — | 실행 이력 참조 |
| `source_id` | BIGINT | FK/IDX | NO | — | 외부 API 참조 |
| `request_url` | TEXT | — | YES | NULL | 실제 호출 URL |
| `request_params_json` | JSONB | — | YES | NULL | 실제 요청 파라미터 |
| `response_body` | JSONB | — | NO | — | 응답 원문 |
| `response_meta_json` | JSONB | — | YES | NULL | 헤더 / 페이지 정보 / 부가 메타 |
| `payload_hash` | VARCHAR(128) | IDX | YES | NULL | 중복 검사용 해시 |
| `collected_at` | TIMESTAMPTZ | IDX | NO | CURRENT_TIMESTAMP | 수집 시각 |
| `retention_expires_at` | TIMESTAMPTZ | IDX | YES | NULL | 보존 만료 시각 |

> - `retention_expires_at = collected_at + 90일` — 90일 보존 정책.
> - `payload_hash` 중복이면 raw 저장 생략 또는 논리 중복 표시.

---

### 2.15 external_api_normalization_error

정규화 실패 추적 테이블.

| 컬럼명 | 데이터 타입 | KEY | NULL | Default | 설명 |
|---|---|---|---|---|---|
| `error_id` | BIGSERIAL | PK | NO | — | 실패 ID |
| `execution_id` | BIGINT | FK/IDX | NO | — | 실행 참조 |
| `raw_id` | BIGINT | FK/IDX | YES | NULL | 원본 payload 참조 |
| `source_id` | BIGINT | FK/IDX | NO | — | 외부 API 참조 |
| `target_table` | VARCHAR(100) | — | NO | — | 적재 대상 테이블 |
| `failed_field` | VARCHAR(100) | — | YES | NULL | 문제 필드 |
| `raw_fragment` | JSONB | — | YES | NULL | 실패 레코드 일부 |
| `error_reason` | TEXT | — | NO | — | 실패 사유 |
| `resolved` | BOOLEAN | — | NO | FALSE | 조치 여부 |
| `resolved_note` | TEXT | — | YES | NULL | 조치 메모 |
| `created_at` | TIMESTAMPTZ | IDX | NO | CURRENT_TIMESTAMP | 생성 시각 |
| `resolved_at` | TIMESTAMPTZ | — | YES | NULL | 조치 완료 시각 |

> - `resolved` 플래그로 미해결 오류 추적.
> - `raw_id` 기준 재정규화 가능.

---

## 3. 인덱스 및 FK 관계 정의

### 3.1 인덱스 정의서

| 테이블 | 인덱스명 | 대상 컬럼 | 목적 |
|---|---|---|---|
| app_user | `idx_user_username` | username | UNIQUE — 로그인 시 중복 방지 |
| shelter | `idx_shelter_shelter_type` | shelter_type | 대피소 유형별 필터링 |
| shelter | `idx_shelter_disaster_type` | disaster_type | 재난 유형별 대피소 조회 |
| shelter | `idx_shelter_status` | shelter_status | 운영 상태별 필터링 |
| shelter | `idx_shelter_location` | latitude, longitude | 좌표 기반 반경 탐색 |
| disaster_alert | `idx_alert_region_type` | region, disaster_type | 지역+유형 복합 조회 |
| disaster_alert | `idx_alert_issued_at` | issued_at DESC | 최신 알림 정렬 |
| disaster_alert_detail | `idx_alert_detail_type` | detail_type | 유형별 상세 조회 |
| evacuation_entry | `idx_entry_shelter_status` | shelter_id, entry_status | 현재인원 COUNT 쿼리 핵심 |
| evacuation_entry | `idx_entry_user` | user_id | 회원별 입소 이력 조회 |
| entry_detail | `idx_detail_entry_id` | entry_id | UNIQUE — 1:1 관계 보장 |
| entry_detail | `idx_detail_health_status` | health_status | 건강 상태별 필터링 |
| entry_detail | `idx_detail_special_protection` | special_protection_flag | 특별관리대상 우선 지원 조회 |
| evacuation_event_history | `idx_history_recorded_at` | recorded_at DESC | 이력 시계열 조회 |
| admin_audit_log | `idx_audit_admin_created` | admin_id, created_at DESC | 관리자별 감사 추적 |
| weather_log | `idx_weather_grid_dt` | nx, ny, forecast_dt DESC | 격자 좌표별 최신 예보 조회 |
| air_quality_log | `idx_air_station_measured` | station_name, measured_at DESC | 측정소별 최신 대기질 조회 |
| external_api_source | `idx_ext_source_category` | category | 카테고리별 조회 |
| external_api_schedule | `idx_ext_schedule_next_run` | next_scheduled_at, is_enabled | 다음 실행 대상 조회 |
| external_api_schedule | `idx_ext_schedule_source` | source_id | 소스별 스케줄 조회 |
| external_api_execution_log | `idx_ext_exec_source_started` | source_id, started_at DESC | 최근 실행 이력 |
| external_api_execution_log | `idx_ext_exec_status_started` | execution_status, started_at DESC | 실패/성공 모니터링 |
| external_api_raw_payload | `idx_ext_raw_source_collected` | source_id, collected_at DESC | 최근 원문 조회 |
| external_api_raw_payload | `idx_ext_raw_payload_hash` | payload_hash | 중복 감지 |
| external_api_normalization_error | `idx_ext_norm_err_source_created` | source_id, created_at DESC | 소스별 오류 조회 |
| external_api_normalization_error | `idx_ext_norm_err_resolved` | resolved, created_at DESC | 미해결 오류 조회 |

### 3.2 FK 관계 정의

| 자식 테이블 | FK 컬럼 | 부모 테이블 | 참조 컬럼 | ON DELETE |
|---|---|---|---|---|
| evacuation_entry | shelter_id | shelter | shelter_id | CASCADE |
| evacuation_entry | alert_id | disaster_alert | alert_id | SET NULL |
| evacuation_entry | user_id | app_user | user_id | SET NULL |
| entry_detail | entry_id | evacuation_entry | entry_id | CASCADE |
| evacuation_event_history | entry_id | evacuation_entry | entry_id | CASCADE |
| evacuation_event_history | shelter_id | shelter | shelter_id | CASCADE |
| evacuation_event_history | recorded_by | app_user | user_id | SET NULL |
| admin_audit_log | admin_id | app_user | user_id | RESTRICT |
| disaster_alert_detail | alert_id | disaster_alert | alert_id | CASCADE |
| external_api_schedule | source_id | external_api_source | source_id | RESTRICT |
| external_api_execution_log | schedule_id | external_api_schedule | schedule_id | RESTRICT |
| external_api_execution_log | source_id | external_api_source | source_id | RESTRICT |
| external_api_raw_payload | execution_id | external_api_execution_log | execution_id | CASCADE |
| external_api_raw_payload | source_id | external_api_source | source_id | RESTRICT |
| external_api_normalization_error | execution_id | external_api_execution_log | execution_id | CASCADE |
| external_api_normalization_error | raw_id | external_api_raw_payload | raw_id | SET NULL |
| external_api_normalization_error | source_id | external_api_source | source_id | RESTRICT |

> - **CASCADE**: 부모 삭제 시 자식 자동 삭제
> - **SET NULL**: 사용자 탈퇴 / raw 삭제 시 기록 유지, FK만 NULL 처리
> - **RESTRICT**: 소스·스케줄·감사 로그는 이력 보존을 위해 함부로 삭제 금지
> - `weather_log`, `air_quality_log`는 외부 API 수집 데이터로 FK 없음

---

## 4. Redis 캐시 운영표

### 4.1 캐시 설계 원칙

| 원칙 | 세부 내용 |
|---|---|
| 파생 데이터만 저장 | DB에서 조회·집계된 결과만 캐시. 원천 데이터(app_user, shelter 등)는 캐시 저장 금지 |
| TTL 필수 | 모든 키에 TTL 설정. TTL 없는 키 배포 금지 |
| 무효화 명시 | 비즈니스 이벤트 발생 시 즉시 해당 키 DEL 후 재생성 (Cache-Aside 패턴) |
| 직렬화 형식 | JSON 직렬화. 파싱 실패 시 DB fallback 처리 |
| 조회 주체 분리 | 사용자(TTL 30초)와 관리자(TTL 5분) 캐시 키 분리 |

### 4.2 캐시 키 운영표

#### 👤 사용자 조회용 (User-facing)

| Redis Key | 의미 | TTL | 생성 주체 | 무효화 조건 |
|---|---|---|---|---|
| `shelter:status:{shelterId}` | 사용자용 대피소 현재인원·잔여인원·혼잡도 | **30초** | cache-worker (입소·퇴소·이송·수정 이벤트 수신 후 RDS COUNT 재계산) | 입소·퇴소·이송 이벤트 즉시 DEL (api-core) |
| `shelter:list:{shelter_type}:{disaster_type}` | 유형+재난별 대피소 목록 | 10분 | api-public-read fallback 재생성 | shelter 마스터 변경 시 DEL 또는 TTL 만료 후 재생성 |
| `disaster:active:{region}` | 지역별 현재 활성 재난 목록 | 2분 | readmodel-worker (DisasterDataCollected 이벤트 수신 후 SET) | expired_at 갱신 / 신규 alert 수신 |
| `disaster:alert:list:{region}:{disasterType}` | 지역+유형별 전체 재난 알림 목록 | 5분 | readmodel-worker (DisasterDataCollected 이벤트 수신 후 SET) | 재난 수집 완료 이벤트 수신 시 / TTL 만료 |
| `disaster:detail:{alertId}` | 개별 재난 알림 상세 | 10분 | readmodel-worker (DisasterDataCollected 이벤트 수신 후 SET) | 해당 alert 만료 / TTL 만료 |
| `env:weather:{nx}:{ny}` | 격자 좌표 기반 날씨 예보 | 60분 | cache-worker (EnvironmentDataCollected 이벤트 수신 후 SET) | TTL 만료 / 갱신 이벤트 |
| `env:air:{station_name}` | 측정소 기반 대기질(AQI) | 60분 | cache-worker (EnvironmentDataCollected 이벤트 수신 후 SET) | TTL 만료 / 갱신 이벤트 |

#### 🔧 관리자 운영용 (Admin-facing)

| Redis Key | 의미 | TTL | 생성 주체 | 무효화 조건 |
|---|---|---|---|---|
| `admin:shelter:status:{shelterId}` | 관리자용 대피소 실시간 인원·혼잡도 | **5분** | cache-worker | 입소·퇴소·이송 이벤트 즉시 DEL (api-core) |
| `admin:dashboard` | 관리자 대시보드 전체 집계 수치 | 1분 | api-core (RDS 직접 조회 후 SET) | TTL 만료 |

> - `env:weather`, `env:air` 캐시 miss 시 → `weather_log`, `air_quality_log`에서 가장 최근 레코드로 fallback
> - Redis 갱신은 external-ingestion이 직접 하지 않고 SQS 이벤트 → async+worker가 담당

---

## 5. 제3정규형(3NF) 검증

### 5.1 검증 결과

| 테이블 | 정규화 결과 | 설계 근거 | 판정 |
|---|---|---|---|
| app_user | 제1~3정규형 만족 | rrn_front_6 암호화 전제로 분리 불필요 | PASS |
| shelter | 제1~3정규형 만족 | shelter_type·disaster_type 분리. 주소·좌표 동시 저장(조회 단순화) | PASS |
| disaster_alert | 제1~3정규형 만족 | region은 외부 API 원문 보존용 | PASS |
| disaster_alert_detail | 제1~3정규형 만족 | disaster_alert와 1:1 분리. 유형별 희소 컬럼 방지 | PASS |
| evacuation_entry | 제1~3정규형 만족 | 현재인원은 COUNT로 계산. 상세 정보는 entry_detail로 분리 | PASS |
| entry_detail | 제1~3정규형 만족 | evacuation_entry와 1:1 분리. 상세 정보 책임 단일화 | PASS |
| evacuation_event_history | 제1~3정규형 만족 | 이벤트 소싱 패턴으로 append-only | PASS |
| admin_audit_log | 제1~3정규형 만족 | payload JSONB는 스냅샷 목적 (집계 X) | PASS |
| weather_log | 제1~3정규형 만족 | 수집 단위(격자+시각)별 단일 책임. 파생값 없음 | PASS |
| air_quality_log | 제1~3정규형 만족 | 수집 단위(측정소+시각)별 단일 책임. grade는 API 원문 보존 목적으로 허용 | PASS |
| external_api_source | 제1~3정규형 만족 | API 메타 단일 책임 | PASS |
| external_api_schedule | 제1~3정규형 만족 | 스케줄 설정 단일 책임 | PASS |
| external_api_execution_log | 제1~3정규형 만족 | 실행 이력 단일 책임 | PASS |
| external_api_raw_payload | 제1~3정규형 만족 | 원본 보관 단일 책임 | PASS |
| external_api_normalization_error | 제1~3정규형 만족 | 실패 추적 단일 책임 | PASS |

### 5.2 설계 결정 사항 (트레이드오프)

| 결정 사항 | 선택한 방향 | 근거 |
|---|---|---|
| user_profile 분리 | 단일 테이블 유지 | 현재 프로필 컬럼 수 적음. 확장 시 분리 용이한 구조 |
| shelter_status 컬럼 | is_active → shelter_status VARCHAR 교체 | 운영중/운영중단/준비중 3단계 필요. boolean으로 표현 불가 |
| 현재인원 저장 여부 | COUNT 쿼리 + 캐시 | 중복 저장 시 트랜잭션 실패로 정합성 붕괴 위험. 3NF 위반 |
| 주소+좌표 동시 저장 | 비정규화 허용 | 지도 API 연동 시 조인 없이 바로 사용. 조회 단순화 목적 |
| audit_log JSONB 컬럼 | JSONB 스냅샷 유지 | 변경 증거 보존 목적. 집계·조회용이 아님 |
| 사용자·관리자 캐시 TTL | 사용자 30초 / 관리자 5분 | 재난 상황 즉각 대응은 사용자 우선. 관리자는 운영 판단으로 지연 허용 |
| entry_detail 분리 | evacuation_entry와 1:1 분리 | 가족관계·건강상태는 선택적 입력. 항상 JOIN할 필요 없어 분리가 유리 |
| weather_log / air_quality_log | RDS 테이블 추가 | 캐시 miss 시 fallback 가능. 수집 이력 보존 |
| air_quality_log grade 컬럼 | API 원문 보존 | value에서 유도 가능하나 기준값 변경 대응 및 API 원문 일치를 위해 저장 |
| 재난 상세 저장 방식 | `disaster_alert_detail` 1:1 분리 | `GET /disasters/{type}/latest` 상세 응답 수용 및 유형별 확장 JSON 처리 |
| 환경 데이터 중복 방지 | 복합 UNIQUE 적용 | external-ingestion 반복 수집 시 중복 적재 방지 |
| 재난 상세 캐시 키 | `disasterType + region` 기준 | API 조회 조건과 캐시 갱신 이벤트 payload 일치 |
| 외부 수집 후 캐시 갱신 | direct Redis write 대신 이벤트 연계 | compute와 async+worker 책임 분리 |
| shelter 외부 upsert 범위 | 7개 컬럼만 허용 | 내부 운영 컬럼 보호 (manager, contact, shelter_status, note) |

---

## 6. ERD 관계 및 데이터 흐름

### 6.1 엔티티 관계

| 관계 | ON DELETE |
|---|---|
| app_user (1) → (0..N) evacuation_entry [user_id] | SET NULL |
| app_user (1) → (0..N) admin_audit_log [admin_id] | RESTRICT |
| app_user (1) → (0..N) evacuation_event_history [recorded_by] | SET NULL |
| shelter (1) → (0..N) evacuation_entry [shelter_id] | CASCADE |
| shelter (1) → (0..N) evacuation_event_history [shelter_id] | CASCADE |
| disaster_alert (0..1) → (0..N) evacuation_entry [alert_id, nullable] | SET NULL |
| disaster_alert (1) → (0..1) disaster_alert_detail [alert_id] | CASCADE |
| evacuation_entry (1) → (0..1) entry_detail [entry_id] | CASCADE |
| evacuation_entry (1) → (1..N) evacuation_event_history [entry_id] | CASCADE |
| external_api_source (1) → (0..N) external_api_schedule [source_id] | RESTRICT |
| external_api_source (1) → (0..N) external_api_execution_log [source_id] | RESTRICT |
| external_api_execution_log (1) → (0..N) external_api_raw_payload [execution_id] | CASCADE |
| external_api_execution_log (1) → (0..N) external_api_normalization_error [execution_id] | CASCADE |
| external_api_raw_payload (1) → (0..N) external_api_normalization_error [raw_id] | SET NULL |
| weather_log — 외부 FK 없음 (독립 수집 테이블) | — |
| air_quality_log — 외부 FK 없음 (독립 수집 테이블) | — |

### 6.2 핵심 데이터 흐름

#### 재난 감지 흐름

```
공공API external-ingestion (Ingestion Pod)
  → external_api_raw_payload (RDS INSERT, 원본 보관)
  → SQS 정규화 큐 투입

Normalizer Pod
  → disaster_alert (RDS INSERT)
  → disaster_alert_detail (RDS INSERT, KMA_EARTHQUAKE인 경우)
  → 캐시 갱신 이벤트 발행 (SQS)

readmodel-worker
  → Redis disaster:active:{region} SET (TTL 2분)
  → Redis disaster:alert:list:{region}:{disasterType} SET (TTL 5분)
  → Redis disaster:detail:{alertId} SET (TTL 10분)
```

#### 입소 처리 흐름

```
POST /admin/evacuation-entries 요청
  → 1. RDS: evacuation_entry INSERT (status=ENTERED, address 포함)
  → 2. RDS: entry_detail INSERT (family_info, health_status, special_protection_flag)
  → 3. RDS: evacuation_event_history INSERT (type=CHECK_IN)
  → 4. Redis: shelter:status:{id} DEL        ← 사용자 캐시 즉시 무효화
  → 5. Redis: admin:shelter:status:{id} DEL  ← 관리자 캐시 즉시 무효화
```

#### 현재인원 조회 흐름 — 사용자 (TTL 30초)

```
클라이언트
  → API Server → Redis shelter:status:{id} HIT → 응답
               → Redis MISS → RDS COUNT(*) WHERE entry_status='ENTERED'
                            → Redis SET (30초) → 응답
```

#### 현재인원 조회 흐름 — 관리자 (TTL 5분)

```
관리자
  → API Server → Redis admin:shelter:status:{id} HIT → 응답
               → Redis MISS → RDS COUNT(*) WHERE entry_status='ENTERED'
                            → Redis SET (5분) → 응답
```

#### 날씨·대기질 수집 흐름

```
external-ingestion CronJob
  → 기상청 / 에어코리아 API 호출
  → external_api_raw_payload (RDS INSERT, 원본 보관)
  → SQS 정규화 큐 투입

Normalizer Pod
  → weather_log / air_quality_log (RDS INSERT)
  → 캐시 갱신 이벤트 발행 (SQS)

cache-worker
  → Redis env:weather:{nx}:{ny} SET (TTL 60분)
  → Redis env:air:{station_name} SET (TTL 60분)

캐시 miss 발생 시
  → api-public-read → RDS 최근 레코드 fallback 조회 → Redis SET → 응답
```

---

## 변경 이력

| 날짜 | 버전 | 변경 내용 |
|---|---|---|
| 2026-04-16 | v6.0 | 최초 작성 |
| 2026-04-19 | v7.0 | disaster_alert_detail 추가 / 재난 상세 캐시 키 통일 / 데이터 흐름 최신화 / health_status 정책 명시 / API↔DB 매핑 확장 / UNIQUE 제약 명시 / shelter 외부 upsert 범위 명시 / external_api_* 테이블 5개 추가 / admin_audit_log reason 저장 정책 추가 |
| 2026-04-20 | v7.1 | 서현 async+worker 문서 기준 Redis 키 전면 수정. disaster:detail:{alertId} / disaster:alert:list 추가 / 생성 주체 cache-worker·readmodel-worker 분리 반영 |
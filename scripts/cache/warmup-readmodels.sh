#!/usr/bin/env bash

# SafeSpot Redis read model manual warmup script.
# Use this before/after deployment to enqueue CacheRegenerationRequested events.
# main merge 후 api-public-read는 Redis GEO 기반 /shelters/nearby를 바로 사용한다.
# feature flag가 없으므로 배포 전후 shelter read model warmup이 필요하다.
# warmup 전에는 /shelters/nearby가 빈 items로 degrade될 수 있다.
# disaster warmup은 기존 readmodel-refresh queue와 cacheKey 기반 rebuild 구조를 유지한다.
# shelter와 disaster는 서로 다른 queue를 사용한다.

set -euo pipefail

DEFAULT_SCOPE="all"
DEFAULT_ONLY="all"
DEFAULT_REGION="ap-northeast-2"
DEFAULT_CHUNK_SIZE="200"

SCOPE="${SCOPE:-}"
ONLY="${ONLY:-}"
SHELTER_IDS_FILE="${SHELTER_IDS_FILE:-}"
DISASTER_ALERT_IDS_FILE="${DISASTER_ALERT_IDS_FILE:-}"
CACHE_REFRESH_QUEUE_URL="${CACHE_REFRESH_QUEUE_URL:-}"
READMODEL_REFRESH_QUEUE_URL="${READMODEL_REFRESH_QUEUE_URL:-}"
AWS_REGION_VALUE="${AWS_REGION:-$DEFAULT_REGION}"
CHUNK_SIZE_VALUE="${CHUNK_SIZE:-$DEFAULT_CHUNK_SIZE}"
DRY_RUN_VALUE="${DRY_RUN:-false}"

usage() {
  cat <<'EOF'
Usage:
  scripts/cache/warmup-readmodels.sh [options]

Options:
  --scope <shelter|disaster|all>
      기본값: all
  --only <target>
      허용값: shelter-map-items, shelter-status, shelter-geo, shelter-tiles, disaster-recent, disaster-core, disaster-list, disaster-detail, all
      기본값: all
  --shelter-ids-file <path>
      shelter_id 목록 파일. 한 줄에 하나의 shelter_id. 빈 줄과 # comment는 무시.
  --disaster-alert-ids-file <path>
      disaster detail rebuild용 alert_id 목록 파일. 한 줄에 하나의 alert_id. 빈 줄과 # comment는 무시.
  --cache-refresh-queue-url <url>
      shelter warmup용 cache-refresh queue URL.
  --readmodel-refresh-queue-url <url>
      disaster warmup용 readmodel-refresh queue URL.
  --region <region>
      기본값: ap-northeast-2
  --chunk-size <n>
      기본값: 200
  --dry-run
      실제 aws sqs send-message를 수행하지 않고 payload만 출력.
  --help
      사용법 출력.

Environment variables:
  AWS_REGION
  CACHE_REFRESH_QUEUE_URL
  READMODEL_REFRESH_QUEUE_URL
  CHUNK_SIZE
  DRY_RUN

Examples:
  1) 전체 warmup dry-run
     scripts/cache/warmup-readmodels.sh \
       --scope all \
       --shelter-ids-file shelter-ids.txt \
       --disaster-alert-ids-file disaster-alert-ids.txt \
       --cache-refresh-queue-url "$CACHE_REFRESH_QUEUE_URL" \
       --readmodel-refresh-queue-url "$READMODEL_REFRESH_QUEUE_URL" \
       --dry-run

  2) 전체 warmup 실행
     scripts/cache/warmup-readmodels.sh \
       --scope all \
       --shelter-ids-file shelter-ids.txt \
       --disaster-alert-ids-file disaster-alert-ids.txt \
       --cache-refresh-queue-url "$CACHE_REFRESH_QUEUE_URL" \
       --readmodel-refresh-queue-url "$READMODEL_REFRESH_QUEUE_URL"

  3) shelter만 warmup
     scripts/cache/warmup-readmodels.sh \
       --scope shelter \
       --shelter-ids-file shelter-ids.txt \
       --cache-refresh-queue-url "$CACHE_REFRESH_QUEUE_URL"

  4) disaster recent/core/list만 warmup
     scripts/cache/warmup-readmodels.sh \
       --scope disaster \
       --only all \
       --readmodel-refresh-queue-url "$READMODEL_REFRESH_QUEUE_URL"

  5) disaster detail만 warmup
     scripts/cache/warmup-readmodels.sh \
       --scope disaster \
       --only disaster-detail \
       --disaster-alert-ids-file disaster-alert-ids.txt \
       --readmodel-refresh-queue-url "$READMODEL_REFRESH_QUEUE_URL"

  6) shelter GEO/TILE만 warmup
     scripts/cache/warmup-readmodels.sh \
       --scope shelter \
       --only shelter-geo \
       --cache-refresh-queue-url "$CACHE_REFRESH_QUEUE_URL"

     scripts/cache/warmup-readmodels.sh \
       --scope shelter \
       --only shelter-tiles \
       --cache-refresh-queue-url "$CACHE_REFRESH_QUEUE_URL"
EOF
}

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

trim() {
  local value="$1"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  printf '%s' "$value"
}

is_positive_integer() {
  [[ "$1" =~ ^[0-9]+$ ]] && [[ "$1" -gt 0 ]]
}

is_truthy() {
  case "${1,,}" in
    1|true|yes|on) return 0 ;;
    *) return 1 ;;
  esac
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "$1 명령을 찾을 수 없습니다."
}

new_uuid() {
  if command -v uuidgen >/dev/null 2>&1; then
    uuidgen
  else
    printf '%s-%s-%s\n' "$(date -u +%Y%m%dT%H%M%S%N)" "$RANDOM" "$$"
  fi
}

utc_now() {
  date -u +"%Y-%m-%dT%H:%M:%SZ"
}

run_id() {
  date -u +"%Y%m%dT%H%M%SZ"
}

sorted_unique_integer_ids_from_file() {
  local file="$1"
  local label="$2"
  [[ -f "$file" ]] || die "$label 파일이 없습니다: $file"

  local ids=()
  local line
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="$(trim "$line")"
    [[ -z "$line" || "$line" == \#* ]] && continue
    [[ "$line" =~ ^[0-9]+$ ]] || die "$label에는 정수만 허용됩니다: $line"
    ids+=("$line")
  done < "$file"

  ((${#ids[@]} > 0)) || die "$label에 유효한 id가 없습니다: $file"

  mapfile -t ids < <(printf '%s\n' "${ids[@]}" | sort -n | uniq)
  printf '%s\n' "${ids[@]}"
}

json_array_from_ids() {
  local -a ids=("$@")
  local json="["
  local i
  for i in "${!ids[@]}"; do
    (( i > 0 )) && json+=","
    json+="${ids[$i]}"
  done
  json+="]"
  printf '%s' "$json"
}

event_body() {
  local event_id="$1"
  local occurred_at="$2"
  local trace_id="$3"
  local idempotency_key="$4"
  local cache_key="$5"
  local cache_key_family="$6"
  local target_type="$7"
  local target_ids_json="$8"
  local requested_at="$9"

  jq -cn \
    --arg eventType "CacheRegenerationRequested" \
    --arg eventId "$event_id" \
    --arg occurredAt "$occurred_at" \
    --arg producer "manual-warmup" \
    --arg traceId "$trace_id" \
    --arg idempotencyKey "$idempotency_key" \
    --arg cacheKey "$cache_key" \
    --arg cacheKeyFamily "$cache_key_family" \
    --arg requestedAt "$requested_at" \
    --arg reason "manual_warmup" \
    --arg schemaVersion "1" \
    --arg targetType "$target_type" \
    --argjson targetIds "$target_ids_json" \
    '{
      eventType: $eventType,
      eventId: $eventId,
      occurredAt: $occurredAt,
      producer: $producer,
      traceId: $traceId,
      idempotencyKey: $idempotencyKey,
      payload: {
        cacheKey: (if $cacheKey == "" then null else $cacheKey end),
        cacheKeyFamily: $cacheKeyFamily,
        requestedAt: $requestedAt,
        reason: $reason,
        schemaVersion: $schemaVersion,
        targetType: (if $targetType == "" then null else $targetType end),
        targetIds: $targetIds
      }
    }'
}

send_message() {
  local queue_url="$1"
  local body="$2"

  if is_truthy "$DRY_RUN_VALUE"; then
    printf -- '--- queue-url: %s ---\n' "$queue_url"
    printf '%s\n' "$body" | jq '.'
    return 0
  fi

  aws sqs send-message \
    --region "$AWS_REGION_VALUE" \
    --queue-url "$queue_url" \
    --message-body "$body" >/dev/null
}

publish_shelter_batch() {
  local queue_url="$1"
  local target_type="$2"
  local cache_key_family="$3"
  local idempotency_prefix="$4"
  local chunk_index="$5"
  local run_id_value="$6"
  local timestamp="$7"
  local trace_id="$8"
  local target_ids_json="$9"
  local body
  local event_id
  local idempotency_key

  event_id="$(new_uuid)"
  idempotency_key="manual-warmup:${idempotency_prefix}:${run_id_value}:chunk-$(printf '%04d' "$chunk_index")"
  body="$(event_body "$event_id" "$timestamp" "$trace_id" "$idempotency_key" "" "$cache_key_family" "$target_type" "$target_ids_json" "$timestamp")"
  send_message "$queue_url" "$body"
}

publish_shelter_target_only() {
  local queue_url="$1"
  local target_type="$2"
  local cache_key_family="$3"
  local idempotency_prefix="$4"
  local run_id_value="$5"
  local timestamp="$6"
  local trace_id="$7"
  local body
  local event_id
  local idempotency_key

  event_id="$(new_uuid)"
  idempotency_key="manual-warmup:${idempotency_prefix}:${run_id_value}"
  body="$(event_body "$event_id" "$timestamp" "$trace_id" "$idempotency_key" "" "$cache_key_family" "$target_type" "null" "$timestamp")"
  send_message "$queue_url" "$body"
}

publish_disaster_cache_key() {
  local queue_url="$1"
  local cache_key="$2"
  local cache_key_family="$3"
  local idempotency_prefix="$4"
  local run_id_value="$5"
  local timestamp="$6"
  local trace_id="$7"
  local body
  local event_id
  local idempotency_key

  event_id="$(new_uuid)"
  idempotency_key="manual-warmup:${idempotency_prefix}:${run_id_value}"
  body="$(event_body "$event_id" "$timestamp" "$trace_id" "$idempotency_key" "$cache_key" "$cache_key_family" "" "null" "$timestamp")"
  send_message "$queue_url" "$body"
}

publish_disaster_detail() {
  local queue_url="$1"
  local alert_id="$2"
  local run_id_value="$3"
  local timestamp="$4"
  local trace_id="$5"
  local body
  local event_id
  local idempotency_key

  event_id="$(new_uuid)"
  idempotency_key="manual-warmup:disaster_detail:${alert_id}:${run_id_value}"
  body="$(event_body "$event_id" "$timestamp" "$trace_id" "$idempotency_key" "disaster:detail:${alert_id}" "disaster_detail" "" "null" "$timestamp")"
  send_message "$queue_url" "$body"
}

validate_scope() {
  case "$1" in
    shelter|disaster|all) ;;
    *) die "--scope 값이 올바르지 않습니다: $1" ;;
  esac
}

validate_only() {
  case "$1" in
    shelter-map-items|shelter-status|shelter-geo|shelter-tiles|disaster-recent|disaster-core|disaster-list|disaster-detail|all) ;;
    *) die "--only 값이 올바르지 않습니다: $1" ;;
  esac
}

scope_allows_only() {
  local scope_value="$1"
  local only_value="$2"
  case "$scope_value" in
    shelter)
      case "$only_value" in
        all|shelter-map-items|shelter-status|shelter-geo|shelter-tiles) return 0 ;;
        *) return 1 ;;
      esac
      ;;
    disaster)
      case "$only_value" in
        all|disaster-recent|disaster-core|disaster-list|disaster-detail) return 0 ;;
        *) return 1 ;;
      esac
      ;;
    all)
      return 0
      ;;
  esac
}

needs_shelter_ids_file() {
  local scope_value="$1"
  local only_value="$2"
  case "$scope_value" in
    shelter)
      case "$only_value" in
        all|shelter-map-items|shelter-status) return 0 ;;
        *) return 1 ;;
      esac
      ;;
    disaster)
      return 1
      ;;
    all)
      case "$only_value" in
        all|shelter-map-items|shelter-status) return 0 ;;
        *) return 1 ;;
      esac
      ;;
  esac
}

needs_disaster_ids_file() {
  local scope_value="$1"
  local only_value="$2"
  case "$scope_value" in
    shelter)
      return 1
      ;;
    disaster)
      case "$only_value" in
        disaster-detail) return 0 ;;
        *) return 1 ;;
      esac
      ;;
    all)
      case "$only_value" in
        disaster-detail) return 0 ;;
        *) return 1 ;;
      esac
      ;;
  esac
}

main() {
  while (($# > 0)); do
    case "$1" in
      --scope)
        [[ $# -ge 2 ]] || die "--scope 값이 필요합니다."
        SCOPE="$2"
        shift 2
        ;;
      --only)
        [[ $# -ge 2 ]] || die "--only 값이 필요합니다."
        ONLY="$2"
        shift 2
        ;;
      --shelter-ids-file)
        [[ $# -ge 2 ]] || die "--shelter-ids-file 값이 필요합니다."
        SHELTER_IDS_FILE="$2"
        shift 2
        ;;
      --disaster-alert-ids-file)
        [[ $# -ge 2 ]] || die "--disaster-alert-ids-file 값이 필요합니다."
        DISASTER_ALERT_IDS_FILE="$2"
        shift 2
        ;;
      --cache-refresh-queue-url)
        [[ $# -ge 2 ]] || die "--cache-refresh-queue-url 값이 필요합니다."
        CACHE_REFRESH_QUEUE_URL="$2"
        shift 2
        ;;
      --readmodel-refresh-queue-url)
        [[ $# -ge 2 ]] || die "--readmodel-refresh-queue-url 값이 필요합니다."
        READMODEL_REFRESH_QUEUE_URL="$2"
        shift 2
        ;;
      --region)
        [[ $# -ge 2 ]] || die "--region 값이 필요합니다."
        AWS_REGION_VALUE="$2"
        shift 2
        ;;
      --chunk-size)
        [[ $# -ge 2 ]] || die "--chunk-size 값이 필요합니다."
        CHUNK_SIZE_VALUE="$2"
        shift 2
        ;;
      --dry-run)
        DRY_RUN_VALUE="true"
        shift
        ;;
      --help|-h)
        usage
        exit 0
        ;;
      *)
        die "알 수 없는 옵션입니다: $1"
        ;;
    esac
  done

  SCOPE="${SCOPE:-$DEFAULT_SCOPE}"
  ONLY="${ONLY:-$DEFAULT_ONLY}"

  validate_scope "$SCOPE"
  validate_only "$ONLY"
  scope_allows_only "$SCOPE" "$ONLY" || die "--scope $SCOPE 과 --only $ONLY 조합은 허용되지 않습니다."
  is_positive_integer "$CHUNK_SIZE_VALUE" || die "--chunk-size는 양의 정수여야 합니다."

  if ! is_truthy "$DRY_RUN_VALUE"; then
    DRY_RUN_VALUE="false"
  else
    DRY_RUN_VALUE="true"
  fi

  require_command aws
  require_command jq

  if [[ "$SCOPE" == "shelter" || "$SCOPE" == "all" ]]; then
    [[ -n "$CACHE_REFRESH_QUEUE_URL" ]] || die "shelter warmup에는 cache-refresh queue URL이 필요합니다."
  fi
  if [[ "$SCOPE" == "disaster" || "$SCOPE" == "all" ]]; then
    [[ -n "$READMODEL_REFRESH_QUEUE_URL" ]] || die "disaster warmup에는 readmodel-refresh queue URL이 필요합니다."
  fi

  local -a shelter_ids=()
  local -a disaster_alert_ids=()

  if needs_shelter_ids_file "$SCOPE" "$ONLY"; then
    [[ -n "$SHELTER_IDS_FILE" ]] || die "shelter warmup에는 --shelter-ids-file이 필요합니다."
    mapfile -t shelter_ids < <(sorted_unique_integer_ids_from_file "$SHELTER_IDS_FILE" "shelter_id")
  fi

  if needs_disaster_ids_file "$SCOPE" "$ONLY"; then
    [[ -n "$DISASTER_ALERT_IDS_FILE" ]] || die "disaster detail warmup에는 --disaster-alert-ids-file이 필요합니다."
    mapfile -t disaster_alert_ids < <(sorted_unique_integer_ids_from_file "$DISASTER_ALERT_IDS_FILE" "alert_id")
  fi

  local run_id_value
  local timestamp
  local trace_id
  run_id_value="$(run_id)"
  timestamp="$(utc_now)"
  trace_id="manual-warmup:${run_id_value}"

  if [[ "$SCOPE" == "shelter" || "$SCOPE" == "all" ]]; then
    local include_map_items=0
    local include_status=0
    local include_geo=0
    local include_tiles=0

    case "$ONLY" in
      all)
        include_map_items=1
        include_status=1
        include_geo=1
        include_tiles=1
        ;;
      shelter-map-items)
        include_map_items=1
        ;;
      shelter-status)
        include_status=1
        ;;
      shelter-geo)
        include_geo=1
        ;;
      shelter-tiles)
        include_tiles=1
        ;;
    esac

    if (( include_map_items || include_status )); then
      local chunk_index=0
      local offset=0
      while (( offset < ${#shelter_ids[@]} )); do
        local end=$((offset + CHUNK_SIZE_VALUE))
        (( end > ${#shelter_ids[@]} )) && end=${#shelter_ids[@]}
        local -a chunk=("${shelter_ids[@]:offset:end-offset}")
        local chunk_json
        chunk_json="$(json_array_from_ids "${chunk[@]}")"
        chunk_index=$((chunk_index + 1))

        if (( include_map_items )); then
          publish_shelter_batch "$CACHE_REFRESH_QUEUE_URL" "SHELTER_MAP_ITEMS" "shelter_map_item" "shelter_map_items" "$chunk_index" "$run_id_value" "$timestamp" "$trace_id" "$chunk_json"
        fi
        if (( include_status )); then
          publish_shelter_batch "$CACHE_REFRESH_QUEUE_URL" "SHELTER_STATUS" "shelter_status" "shelter_status" "$chunk_index" "$run_id_value" "$timestamp" "$trace_id" "$chunk_json"
        fi

        offset="$end"
      done
    fi

    if (( include_geo )); then
      publish_shelter_target_only "$CACHE_REFRESH_QUEUE_URL" "SHELTER_GEO_INDEX" "shelter_geo_index" "shelter_geo_index" "$run_id_value" "$timestamp" "$trace_id"
    fi

    if (( include_tiles )); then
      publish_shelter_target_only "$CACHE_REFRESH_QUEUE_URL" "SHELTER_MAP_TILES" "shelter_map_tile" "shelter_map_tiles" "$run_id_value" "$timestamp" "$trace_id"
    fi
  fi

  if [[ "$SCOPE" == "disaster" || "$SCOPE" == "all" ]]; then
    local include_recent=0
    local include_core=0
    local include_list=0
    local include_detail=0

    case "$ONLY" in
      all)
        include_recent=1
        include_core=1
        include_list=1
        ;;
      disaster-recent)
        include_recent=1
        ;;
      disaster-core)
        include_core=1
        ;;
      disaster-list)
        include_list=1
        ;;
      disaster-detail)
        include_detail=1
        ;;
    esac

    if (( include_recent )); then
      publish_disaster_cache_key "$READMODEL_REFRESH_QUEUE_URL" "disaster:messages:recent:seoul" "disaster_messages" "disaster_messages_recent" "$run_id_value" "$timestamp" "$trace_id"
    fi
    if (( include_core )); then
      publish_disaster_cache_key "$READMODEL_REFRESH_QUEUE_URL" "disaster:message:core:seoul" "disaster_messages" "disaster_message_core" "$run_id_value" "$timestamp" "$trace_id"
    fi
    if (( include_list )); then
      publish_disaster_cache_key "$READMODEL_REFRESH_QUEUE_URL" "disaster:messages:list:seoul" "disaster_messages" "disaster_messages_list" "$run_id_value" "$timestamp" "$trace_id"
    fi
    if (( include_detail )); then
      local offset=0
      while (( offset < ${#disaster_alert_ids[@]} )); do
        local end=$((offset + CHUNK_SIZE_VALUE))
        (( end > ${#disaster_alert_ids[@]} )) && end=${#disaster_alert_ids[@]}
        local -a chunk=("${disaster_alert_ids[@]:offset:end-offset}")
        local alert_id
        for alert_id in "${chunk[@]}"; do
          publish_disaster_detail "$READMODEL_REFRESH_QUEUE_URL" "$alert_id" "$run_id_value" "$timestamp" "$trace_id"
        done
        offset="$end"
      done
    fi
  fi

  cat <<'EOF' >&2
Redis 확인 안내:
Shelter:
- redis-cli --scan --pattern 'shelter:map:item:*' | head
- redis-cli --scan --pattern 'shelter:status:*' | head
- redis-cli --scan --pattern 'shelter:geo:seoul:*' | head
- redis-cli --scan --pattern 'shelter:map:tile:*' | head
- redis-cli EXISTS shelter:geo:seoul:all:all
- redis-cli ZCARD shelter:geo:seoul:all:all

Disaster:
- redis-cli EXISTS disaster:messages:recent:seoul
- redis-cli EXISTS disaster:message:core:seoul
- redis-cli EXISTS disaster:messages:list:seoul
- redis-cli --scan --pattern 'disaster:detail:*' | head
EOF
}

main "$@"

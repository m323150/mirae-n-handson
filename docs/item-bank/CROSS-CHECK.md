# legacy/item-bank-php 비즈니스 규칙 추출 (교차 검증용)

- 읽은 파일: `legacy/item-bank-php/` 의 `search.php`, `register.php`, `units.php`, `index.php`, `inc/db.php` (`vendor/simplelog` 는 읽지 않음). `docs/` 는 읽지 않았다.
- 기준: 코드가 우선이다. 주석과 코드가 다르면 코드대로 적고 "주석 불일치"로 표시했다.
- 경로는 모두 `legacy/item-bank-php/` 기준 상대 경로다.

## 범위 밖 · 확인된 공백

- **수정(edit) 기능은 이 폴더에 없다.** 파일은 `index.php`, `register.php`, `search.php`, `units.php`, `inc/`, `vendor/` 뿐이고, 메뉴에도 수정 항목이 없다(`index.php:9-11`). 따라서 "수정 시 검증" 규칙은 추출할 수 없으며, 쓰지 않는다.
- **`v_item_public` 뷰의 정의는 이 폴더에 없다.** 검색이 이 뷰에서 조회한다는 사실(CX-13)만 코드로 확인되고, 뷰가 어떤 행을 거르는지는 이 폴더의 코드만으로는 알 수 없다.

## 검색 — 조건 조합

### CX-01 키워드는 제목 또는 지문에 부분 일치한다
- 규칙: 키워드 `q` 는 앞뒤 공백을 제거한 뒤 `title` 또는 `stem` 중 하나라도 `%q%` 로 LIKE 일치하면 통과한다. 입력 속 `%` · `_` 는 이스케이프하지 않아 와일드카드로 동작한다.
- 근거: `search.php:97-98`, `search.php:85`, `search.php:94-96`
```php
$like = '%' . $q . '%';
$where .= " AND (title LIKE ? OR stem LIKE ?)";
```

### CX-02 키워드는 100자까지만 쓴다
- 규칙: 트림한 키워드가 100자를 넘으면 앞 100자로 자르고 경고만 남긴다(거절하지 않음).
- 근거: `search.php:86-89`
```php
if (mb_strlen($q, 'UTF-8') > 100) {
    $q = mb_substr($q, 0, 100, 'UTF-8');
```

### CX-03 단원은 코드가 정확히 같아야 하며, 형식이 틀려도 조회한다
- 규칙: `unit` 은 `unit_code = ?` 완전 일치로 거른다. 형식(`^[A-Za-z][0-9]{1,2}-[0-9]{1,2}$`)이 틀리거나 소문자가 섞여도 경고만 하고 입력값 그대로 조회한다(대개 0건).
- 근거: `search.php:111-114`, `search.php:115-117`, `search.php:118-120`
```php
if (!preg_match('/^[A-Za-z][0-9]{1,2}-[0-9]{1,2}$/', $unit)) {
    // 형식이 달라도 그대로 조회한다 (결과는 대개 0건)
$where .= " AND unit_code = ?";
```

### CX-04 난이도를 비우면 난이도 5 문항이 제외된다 (주석 불일치)
- 규칙: `level` 이 빈 값이면 `level < 5` 조건이 붙어 난이도 5 문항은 결과에서 빠진다. 난이도 5 문항은 `level=5` 를 명시해야만 검색된다.
- 주석 불일치: 바로 위 주석은 "전체 난이도 검색 (1~5 모두 포함)"(`search.php:213`)이라고 하지만 코드는 5를 제외한다. 코드를 따른다.
- 근거: `search.php:214-216`
```php
if ($level == '') {
    $where .= " AND level < 5";
}
```

### CX-05 난이도 1~5 를 주면 그 값과 정확히 같은 문항만 나온다
- 규칙: `level` 이 `1`~`5` 한 자리 숫자면 `level = ?` 로 거른다(범위 검색이 아님).
- 근거: `search.php:217-221`
```php
else if (preg_match('/^[1-5]$/', $level)) {
    $where .= " AND level = ?";
```

### CX-06 1~5 밖의 난이도는 거절하지 않고 정수로 바꿔 비교한다
- 규칙: `level` 이 `^[1-5]$` 에 맞지 않는 비어 있지 않은 값이면 경고를 남기고 `(int)` 변환한 값으로 `level = ?` 비교를 한다(숫자가 아닌 문자열은 0이 된다). 결과는 대개 0건이다.
- 근거: `search.php:223-229`
```php
$warnings[] = '난이도는 1~5 사이여야 합니다.';
$where .= " AND level = ?";
$values[] = (int)$level;
```

### CX-07 태그는 이름이 정확히 같은 태그가 달린 문항만 나오고, 50자까지만 쓴다
- 규칙: `tag` 는 `tag.name = ?` 완전 일치이며 `item_tag` 에 해당 태그가 연결된 문항만 남긴다(`EXISTS`). 부분 일치는 지원하지 않고, 50자를 넘으면 자른다. 등록되지 않은 태그 이름이어도 조회는 하며(0건) 경고만 남긴다.
- 근거: `search.php:244-245`, `search.php:240-243`, `search.php:258-260`
```php
$where .= " AND EXISTS (SELECT 1 FROM item_tag it JOIN tag t ON t.id = it.tag_id"
        . " WHERE it.item_id = v_item_public.id AND t.name = ?)";
```

### CX-08 키워드 · 단원 · 난이도 · 태그 조건은 모두 AND 로 조합한다
- 규칙: 주어진 조건은 각각 `WHERE 1=1` 뒤에 `AND` 로 이어 붙는다. OR 조합은 없다. 총 건수 쿼리(`count_sql`)도 같은 `$where` 를 써서 목록과 건수가 같은 조건을 따른다.
- 근거: `search.php:44`, `search.php:525-526`
```php
$where    = " WHERE 1=1";
$countSql = "SELECT COUNT(*) AS cnt" . $from . $where;
```

## 검색 — 정렬 · 제외 · 페이지

### CX-09 기본 정렬은 난이도 내림차순, 같으면 id 오름차순이다
- 규칙: `sort` 가 비어 있으면 어려운 문항부터, 난이도가 같으면 번호 순으로 정렬한다.
- 근거: `search.php:317-320`
```php
case '':
    // 기본 정렬: 어려운 문항부터, 같은 난이도면 번호 순
    $orderBy = " ORDER BY level DESC, id ASC";
```

### CX-10 정렬 기준별 기본 방향과 동점 처리
- 규칙: `sort` 는 `id | title | unit | level | created` 만 받는다. `dir` 을 주지 않거나 잘못 주면 `level`·`created` 는 내림차순, 나머지(`id`, `title`, `unit`)는 오름차순이다. `unit` 정렬은 `unit_code` 다음에 `level DESC` 를 쓴다. `id` 정렬을 뺀 나머지는 동점을 `id ASC` 로 푼다.
- 근거: `search.php:301-307`, `search.php:293-298`, `search.php:268-273`
```php
case 'level':
    if ($dir === 'asc') {
        $orderBy = " ORDER BY level ASC, id ASC";
$orderBy = " ORDER BY unit_code ASC, level DESC, id ASC";
```

### CX-11 알 수 없는 정렬 기준 · 방향은 오류 없이 기본값으로 돌아간다
- 규칙: 모르는 `sort` 값은 경고를 남기고 기본 정렬(CX-09)을 쓴다. `asc`/`desc` 가 아닌 `dir` 은 경고 후 없는 것으로 취급한다.
- 근거: `search.php:322-326`, `search.php:268-272`
```php
default:
    $warnings[] = '알 수 없는 정렬 기준입니다. 기본 정렬을 사용합니다.';
    $orderBy = " ORDER BY level DESC, id ASC";
```

### CX-12 한 페이지는 20건이고 페이지 번호는 1~999 로 보정한다
- 규칙: 페이지 크기는 20건이다. 숫자가 아니거나 1 미만이면 1페이지, 999 초과면 999 로 맞추고 경고를 남긴다.
- 근거: `search.php:523`, `search.php:342-348`
```php
$limit  = " LIMIT 20 OFFSET " . (int)$offset;
if ($page > 999) {
    $page = 999;
```

### CX-13 검색은 공개 문항 뷰(`v_item_public`)에서만 조회한다
- 규칙: 검색과 건수 조회의 대상은 `item` 테이블이 아니라 `v_item_public` 뷰다. 따라서 이 뷰에서 빠지는 문항은 어떤 조건으로도 검색되지 않는다. (뷰의 필터 조건 자체는 이 폴더에서 확인할 수 없다.)
- 근거: `search.php:522`, `search.php:519`
```php
$from   = " FROM v_item_public";
```

### CX-14 단원별 공개 문항 수는 `status = 'A'` 만 센다
- 규칙: 단원 목록의 공개 문항 수는 `status='A'` 인 문항만 센다. 삭제 · 검수중 문항은 세지 않는다.
- 근거: `units.php:15`, `units.php:17`
```php
// 단원별 공개(status='A') 문항 수. 삭제 · 검수중 문항은 세지 않는다.
(SELECT COUNT(*) FROM item i WHERE i.unit_id = u.id AND i.status = 'A') AS item_count
```

## 등록 — 검증 (`register.php`)

### CX-15 제목은 앞뒤 공백을 뺀 뒤 5자 이상 200자 이하다
- 규칙: 제목은 `trim` 후 길이를 잰다(내부 공백은 글자 수에 포함). 5자 미만이거나 200자 초과면 각각 별도 오류가 나며 저장하지 않는다.
- 근거: `register.php:41`, `register.php:49-54`
```php
if (mb_strlen($title, 'UTF-8') < 5) {
if (mb_strlen($title, 'UTF-8') > 200) {
```

### CX-16 지문은 필수이며 공백만 있으면 안 된다
- 규칙: 지문은 `trim` 한 결과가 빈 문자열이면 오류다. 길이 상한은 이 코드에 없다.
- 근거: `register.php:42`, `register.php:56-58`
```php
if ($stem === '') {
    $errors[] = '지문을 입력해야 합니다.';
```

### CX-17 단원은 필수이며 `unit` 테이블에 있는 id 여야 한다
- 규칙: 폼의 `unit_id` 가 단원 목록(`unit.id`)의 값과 문자열로 일치해야 한다. 비었거나 없는 id 면 같은 오류 하나로 처리한다.
- 근거: `register.php:60-68`
```php
if ((string)$u['id'] === $unitId) {
if (!$unitOk) {
    $errors[] = '단원을 선택해야 합니다.';
```

### CX-18 난이도는 1~5 한 자리 정수여야 한다
- 규칙: `level` 은 `^[1-5]$` 에 맞아야 한다. 비어 있어도 오류다(등록에서는 필수).
- 근거: `register.php:70-72`
```php
if (!preg_match('/^[1-5]$/', $level)) {
    $errors[] = '난이도는 1~5 사이여야 합니다.';
```

### CX-19 태그는 선택 사항이며, 목록에 없는 태그 id 는 오류 없이 버린다
- 규칙: 태그는 없어도 된다. 제출된 태그 id 중 `tag` 목록에 없는 것은 오류로 알리지 않고 저장 대상에서 조용히 뺀다.
- 근거: `register.php:74-81`, `register.php:104`
```php
if ((string)$t['id'] === (string)$tid) {
    $validTagIds[] = (int)$tid;
```

### CX-20 새 문항은 검수중(`R`) 상태로 저장되어 검색에 나오지 않는다
- 규칙: 등록된 문항의 `status` 는 항상 `'R'` 이다. 주석은 검수 완료 전까지 검색 화면에 나오지 않는다고 하며, 검색이 `status` 로 거르는지는 뷰 정의에 달려 있다(CX-13). 이 폴더의 코드에는 `R` 을 `A` 로 바꾸는 검수 기능이 없다.
- 근거: `register.php:93-94`, `register.php:84`
```php
"INSERT INTO item (id, unit_id, title, stem, level, status, created_at, updated_at)
 VALUES (?, ?, ?, ?, ?, 'R', NOW(), NOW())"
```

## 교차 검증 시 눈여겨볼 점

- CX-04 는 주석과 코드가 다른 대표 사례다. 난이도 5 문항은 등록 화면에서 만들 수 있는데(CX-18), 난이도 없이 검색하면 나오지 않는다.
- CX-03 · CX-06 · CX-07 은 "잘못된 입력을 거절하지 않고 경고 후 그대로 조회"하는 동작이다. 새 API 가 400 을 돌려주면 레거시와 달라진다.
- CX-19 는 오류 없이 값을 버리는 동작이고, CX-20 의 공개 기준은 뷰 정의(이 폴더 밖)를 봐야 확정된다.

<?php

declare(strict_types=1);

$configFile = __DIR__ . '/config.php';
if (!is_file($configFile)) {
    respond(500, 'Missing config.php. Copy config.example.php to config.php first.');
}

$config = require $configFile;
$token = trim((string)($config['token'] ?? ''));
$storageDir = (string)($config['storage_dir'] ?? (__DIR__ . '/data'));
$maxUploadBytes = (int)($config['max_upload_bytes'] ?? (50 * 1024 * 1024));
$maxGpxUploadBytes = (int)($config['max_gpx_upload_bytes'] ?? (10 * 1024 * 1024));
$backupFile = rtrim($storageDir, "/\\") . DIRECTORY_SEPARATOR . 'horse_tracker_backup.zip';

if ($token === '' || $token === 'CHANGE_ME_TO_A_LONG_RANDOM_TOKEN') {
    respond(500, 'Server token is not configured.');
}

$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';

if ($method === 'GET' && wantsHtml() && !isset($_GET['download'])) {
    if (!isAuthorized($token)) {
        renderLoginPage();
    }
    renderImportPage($backupFile, tokenFromRequest());
}

if ($method === 'GET' && isset($_GET['download'])) {
    if (!isAuthorized($token)) {
        renderLoginPage('Neplatny token.');
    }
    sendBackup($backupFile);
}

if ($method === 'POST') {
    if (!isAuthorized($token)) {
        renderLoginPage('Neplatny token.');
    }
    handleGpxImport($backupFile, tokenFromRequest(), $maxGpxUploadBytes);
}

requireBearerToken($token);

if ($method === 'PUT') {
    saveBackup($backupFile, $maxUploadBytes);
    respond(200, 'OK');
}

if ($method === 'GET') {
    sendBackup($backupFile);
}

if ($method === 'HEAD') {
    if (!is_file($backupFile)) {
        http_response_code(404);
        exit;
    }
    header('Content-Type: application/zip');
    header('Content-Length: ' . filesize($backupFile));
    exit;
}

header('Allow: GET, POST, PUT, HEAD');
respond(405, 'Method not allowed');

function requireBearerToken(string $expectedToken): void
{
    $actualToken = bearerToken();
    if ($actualToken === null) {
        respond(401, 'Missing bearer token');
    }

    if (!hash_equals($expectedToken, $actualToken)) {
        respond(403, 'Invalid bearer token');
    }
}

function isAuthorized(string $expectedToken): bool
{
    $actualToken = bearerToken() ?? tokenFromRequest();
    return $actualToken !== '' && hash_equals($expectedToken, $actualToken);
}

function bearerToken(): ?string
{
    $header = $_SERVER['HTTP_AUTHORIZATION'] ?? $_SERVER['REDIRECT_HTTP_AUTHORIZATION'] ?? '';
    if ($header === '' && function_exists('apache_request_headers')) {
        $headers = apache_request_headers();
        $header = $headers['Authorization'] ?? $headers['authorization'] ?? '';
    }

    $prefix = 'Bearer ';
    if (substr($header, 0, strlen($prefix)) !== $prefix) {
        return null;
    }

    return substr($header, strlen($prefix));
}

function tokenFromRequest(): string
{
    return trim((string)($_POST['token'] ?? $_GET['token'] ?? ''));
}

function wantsHtml(): bool
{
    $accept = $_SERVER['HTTP_ACCEPT'] ?? '';
    return $accept === '' || stripos($accept, 'text/html') !== false;
}

function saveBackup(string $backupFile, int $maxUploadBytes): void
{
    $lengthHeader = $_SERVER['CONTENT_LENGTH'] ?? null;
    if ($lengthHeader !== null && (int)$lengthHeader > $maxUploadBytes) {
        respond(413, 'Backup is too large');
    }

    $dir = dirname($backupFile);
    if (!is_dir($dir) && !mkdir($dir, 0700, true) && !is_dir($dir)) {
        respond(500, 'Cannot create storage directory');
    }

    $lockFile = $backupFile . '.lock';
    $lockHandle = fopen($lockFile, 'c');
    if ($lockHandle === false || !flock($lockHandle, LOCK_EX)) {
        respond(500, 'Cannot lock backup file');
    }

    $tempFile = $backupFile . '.tmp';
    $input = fopen('php://input', 'rb');
    $output = fopen($tempFile, 'wb');
    if ($input === false || $output === false) {
        respond(500, 'Cannot open upload streams');
    }

    $bytes = 0;
    while (!feof($input)) {
        $chunk = fread($input, 1024 * 1024);
        if ($chunk === false) {
            @unlink($tempFile);
            respond(500, 'Cannot read upload');
        }
        $bytes += strlen($chunk);
        if ($bytes > $maxUploadBytes) {
            @unlink($tempFile);
            respond(413, 'Backup is too large');
        }
        fwrite($output, $chunk);
    }

    fclose($input);
    fclose($output);

    if ($bytes === 0) {
        @unlink($tempFile);
        respond(400, 'Empty backup upload');
    }

    if (!rename($tempFile, $backupFile)) {
        @unlink($tempFile);
        respond(500, 'Cannot save backup');
    }

    @chmod($backupFile, 0600);
    flock($lockHandle, LOCK_UN);
    fclose($lockHandle);
}

function sendBackup(string $backupFile): void
{
    if (!is_file($backupFile)) {
        respond(404, 'No backup saved yet');
    }

    header('Content-Type: application/zip');
    header('Content-Disposition: attachment; filename="horse_tracker_backup.zip"');
    header('Content-Length: ' . filesize($backupFile));
    readfile($backupFile);
    exit;
}

function handleGpxImport(string $backupFile, string $requestToken, int $maxGpxUploadBytes): void
{
    try {
        if (!class_exists('ZipArchive')) {
            throw new RuntimeException('Na serveru chybi PHP rozsireni ZipArchive.');
        }
        if (!class_exists('DOMDocument')) {
            throw new RuntimeException('Na serveru chybi PHP DOM XML rozsireni.');
        }

        $upload = $_FILES['gpx'] ?? null;
        if (!is_array($upload) || (int)($upload['error'] ?? UPLOAD_ERR_NO_FILE) !== UPLOAD_ERR_OK) {
            throw new RuntimeException('Vyber GPX soubor.');
        }

        $size = (int)($upload['size'] ?? 0);
        if ($size <= 0) {
            throw new RuntimeException('GPX soubor je prazdny.');
        }
        if ($size > $maxGpxUploadBytes) {
            throw new RuntimeException('GPX soubor je prilis velky.');
        }

        $sourcePath = (string)$upload['tmp_name'];
        $gpxBytes = file_get_contents($sourcePath);
        if ($gpxBytes === false || $gpxBytes === '') {
            throw new RuntimeException('GPX soubor nejde precist.');
        }

        $stats = parseGpxStats($gpxBytes);
        $entries = loadBackupEntries($backupFile);
        ensureBackupDefaults($entries);
        $horses = readHorses($entries);
        $horseId = resolveImportHorseId($horses);
        writeHorses($entries, $horses);

        $meta = [
            'horseId' => $horseId,
            'startTimeMs' => $stats['startTimeMs'],
            'endTimeMs' => $stats['endTimeMs'],
            'distanceM' => $stats['distanceM'],
            'avgSpeedMps' => $stats['avgSpeedMps'],
            'maxSpeedMps' => $stats['maxSpeedMps'],
            'pointsCount' => $stats['pointsCount'],
        ];

        if (rideAlreadyExists($entries, $meta)) {
            throw new RuntimeException('Tahle trasa uz v backupu pravdepodobne existuje.');
        }

        $horseName = horseNameById($horses, $horseId) ?? 'UnknownHorse';
        $baseName = buildRideBaseName($stats['startTimeMs'], $horseName);
        $uniqueBase = uniqueRideBaseName($entries, $baseName);
        $gpxName = $uniqueBase . '.gpx';
        $metaName = $uniqueBase . '.meta.json';
        $meta['gpxFileName'] = $gpxName;

        $entries['rides/' . $gpxName] = $gpxBytes;
        $entries['rides/' . $metaName] = json_encode($meta, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES) . "\n";
        saveBackupEntries($backupFile, $entries);

        $message =
            'Import hotov: ' . $horseName .
            ', ' . date('Y-m-d H:i', (int)floor($stats['startTimeMs'] / 1000)) .
            ', ' . number_format($stats['distanceM'] / 1000, 2, ',', ' ') . ' km.';
        renderImportPage($backupFile, $requestToken, $message);
    } catch (Throwable $e) {
        renderImportPage($backupFile, $requestToken, $e->getMessage(), true);
    }
}

function resolveImportHorseId(array &$horses): string
{
    $newHorse = trim((string)($_POST['newHorseName'] ?? ''));
    if ($newHorse !== '') {
        foreach ($horses as $horse) {
            if (lowerUtf8(trim((string)$horse['name'])) === lowerUtf8($newHorse)) {
                return (string)$horse['id'];
            }
        }
        $horse = ['id' => uuidV4(), 'name' => $newHorse];
        $horses[] = $horse;
        return $horse['id'];
    }

    $horseId = trim((string)($_POST['horseId'] ?? ''));
    foreach ($horses as $horse) {
        if ((string)$horse['id'] === $horseId) {
            return $horseId;
        }
    }

    throw new RuntimeException('Vyber kone nebo zadej noveho.');
}

function lowerUtf8(string $value): string
{
    if (function_exists('mb_strtolower')) {
        return mb_strtolower($value, 'UTF-8');
    }
    return strtolower($value);
}

function parseGpxStats(string $gpxBytes): array
{
    $previous = null;
    $pointsCount = 0;
    $distanceM = 0.0;
    $maxSpeedMps = 0.0;
    $startTimeMs = null;
    $endTimeMs = null;

    $old = libxml_use_internal_errors(true);
    $dom = new DOMDocument();
    $loaded = $dom->loadXML($gpxBytes, LIBXML_NONET);
    libxml_clear_errors();
    libxml_use_internal_errors($old);
    if (!$loaded) {
        throw new RuntimeException('GPX neni platne XML.');
    }

    $xpath = new DOMXPath($dom);
    $nodes = $xpath->query('//*[local-name()="trkpt"]');
    if ($nodes === false || $nodes->length < 2) {
        throw new RuntimeException('GPX musi obsahovat aspon dva body trasy.');
    }

    foreach ($nodes as $node) {
        if (!$node instanceof DOMElement) {
            continue;
        }
        $lat = filter_var($node->getAttribute('lat'), FILTER_VALIDATE_FLOAT);
        $lon = filter_var($node->getAttribute('lon'), FILTER_VALIDATE_FLOAT);
        if ($lat === false || $lon === false || $lat < -90 || $lat > 90 || $lon < -180 || $lon > 180) {
            continue;
        }

        $timeNode = $xpath->query('./*[local-name()="time"][1]', $node);
        $timeMs = null;
        if ($timeNode !== false && $timeNode->length > 0) {
            $text = trim((string)$timeNode->item(0)->textContent);
            if ($text !== '') {
                $timeMs = parseGpxTimeMs($text);
            }
        }

        $point = ['lat' => (float)$lat, 'lon' => (float)$lon, 'timeMs' => $timeMs];
        if ($previous !== null) {
            $segmentM = haversineMeters($previous['lat'], $previous['lon'], $point['lat'], $point['lon']);
            $distanceM += $segmentM;
            if ($previous['timeMs'] !== null && $point['timeMs'] !== null) {
                $durationS = ($point['timeMs'] - $previous['timeMs']) / 1000.0;
                if ($durationS > 0) {
                    $maxSpeedMps = max($maxSpeedMps, $segmentM / $durationS);
                }
            }
        }

        if ($timeMs !== null) {
            $startTimeMs = $startTimeMs ?? $timeMs;
            $endTimeMs = $timeMs;
        }

        $pointsCount++;
        $previous = $point;
    }

    if ($pointsCount < 2) {
        throw new RuntimeException('GPX nema dost platnych bodu trasy.');
    }
    if ($startTimeMs === null || $endTimeMs === null) {
        throw new RuntimeException('GPX neobsahuje casy bodu trasy.');
    }

    $durationS = max(1.0, ($endTimeMs - $startTimeMs) / 1000.0);
    return [
        'startTimeMs' => $startTimeMs,
        'endTimeMs' => $endTimeMs,
        'distanceM' => $distanceM,
        'avgSpeedMps' => $distanceM / $durationS,
        'maxSpeedMps' => $maxSpeedMps,
        'pointsCount' => $pointsCount,
    ];
}

function parseGpxTimeMs(string $text): ?int
{
    try {
        $date = new DateTimeImmutable($text);
        return ((int)$date->format('U') * 1000) + intdiv((int)$date->format('u'), 1000);
    } catch (Throwable $e) {
        return null;
    }
}

function haversineMeters(float $lat1, float $lon1, float $lat2, float $lon2): float
{
    $earthRadiusM = 6371000.0;
    $dLat = deg2rad($lat2 - $lat1);
    $dLon = deg2rad($lon2 - $lon1);
    $a =
        sin($dLat / 2) * sin($dLat / 2) +
        cos(deg2rad($lat1)) * cos(deg2rad($lat2)) *
        sin($dLon / 2) * sin($dLon / 2);
    return $earthRadiusM * 2 * atan2(sqrt($a), sqrt(1 - $a));
}

function loadBackupEntries(string $backupFile): array
{
    if (!is_file($backupFile)) {
        return [];
    }
    if (!class_exists('ZipArchive')) {
        throw new RuntimeException('Na serveru chybi PHP rozsireni ZipArchive.');
    }

    $zip = new ZipArchive();
    if ($zip->open($backupFile) !== true) {
        throw new RuntimeException('Backup ZIP nejde otevrit.');
    }

    $entries = [];
    for ($i = 0; $i < $zip->numFiles; $i++) {
        $name = $zip->getNameIndex($i);
        if ($name === false || substr($name, -1) === '/') {
            continue;
        }
        $safePath = sanitizeZipPath($name);
        if ($safePath === null) {
            continue;
        }
        $data = $zip->getFromIndex($i);
        if ($data !== false) {
            $entries[$safePath] = $data;
        }
    }
    $zip->close();
    return $entries;
}

function saveBackupEntries(string $backupFile, array $entries): void
{
    $dir = dirname($backupFile);
    if (!is_dir($dir) && !mkdir($dir, 0700, true) && !is_dir($dir)) {
        throw new RuntimeException('Nelze vytvorit storage adresar.');
    }

    $lockFile = $backupFile . '.lock';
    $lockHandle = fopen($lockFile, 'c');
    if ($lockHandle === false || !flock($lockHandle, LOCK_EX)) {
        throw new RuntimeException('Nelze zamknout backup.');
    }

    $tempFile = $backupFile . '.tmp';
    $zip = new ZipArchive();
    if ($zip->open($tempFile, ZipArchive::CREATE | ZipArchive::OVERWRITE) !== true) {
        flock($lockHandle, LOCK_UN);
        fclose($lockHandle);
        throw new RuntimeException('Nelze vytvorit ZIP.');
    }

    ksort($entries);
    foreach ($entries as $name => $data) {
        $safePath = sanitizeZipPath((string)$name);
        if ($safePath === null) {
            continue;
        }
        $zip->addFromString($safePath, (string)$data);
    }
    $zip->close();

    if (!rename($tempFile, $backupFile)) {
        @unlink($tempFile);
        flock($lockHandle, LOCK_UN);
        fclose($lockHandle);
        throw new RuntimeException('Nelze ulozit backup.');
    }

    @chmod($backupFile, 0600);
    flock($lockHandle, LOCK_UN);
    fclose($lockHandle);
}

function ensureBackupDefaults(array &$entries): void
{
    if (!isset($entries['backup.json'])) {
        $entries['backup.json'] = json_encode([
            'version' => 1,
            'selectedHorseId' => null,
            'warnThresholdM' => 30.0,
            'backOnRouteThresholdM' => 5.0,
        ], JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES) . "\n";
    }
    if (!isset($entries['horses/horses.json'])) {
        $entries['horses/horses.json'] = json_encode(['horses' => []], JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES) . "\n";
    }
}

function readHorses(array $entries): array
{
    $json = json_decode((string)($entries['horses/horses.json'] ?? ''), true);
    $rows = is_array($json['horses'] ?? null) ? $json['horses'] : [];
    $horses = [];
    foreach ($rows as $row) {
        $id = trim((string)($row['id'] ?? ''));
        $name = trim((string)($row['name'] ?? ''));
        if ($id !== '' && $name !== '') {
            $horses[] = ['id' => $id, 'name' => $name];
        }
    }
    return $horses;
}

function writeHorses(array &$entries, array $horses): void
{
    $entries['horses/horses.json'] = json_encode(['horses' => array_values($horses)], JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE) . "\n";
}

function rideAlreadyExists(array $entries, array $meta): bool
{
    foreach ($entries as $name => $data) {
        if (!preg_match('/^rides\/.+\.meta\.json$/', (string)$name)) {
            continue;
        }
        $existing = json_decode((string)$data, true);
        if (!is_array($existing)) {
            continue;
        }
        if (
            (string)($existing['horseId'] ?? '') === (string)$meta['horseId'] &&
            abs((int)($existing['startTimeMs'] ?? 0) - (int)$meta['startTimeMs']) <= 1000 &&
            abs((int)($existing['endTimeMs'] ?? 0) - (int)$meta['endTimeMs']) <= 1000 &&
            (int)($existing['pointsCount'] ?? -1) === (int)$meta['pointsCount']
        ) {
            return true;
        }
    }
    return false;
}

function horseNameById(array $horses, string $horseId): ?string
{
    foreach ($horses as $horse) {
        if ((string)$horse['id'] === $horseId) {
            return (string)$horse['name'];
        }
    }
    return null;
}

function computeHorseStats(array $entries, array $horses): array
{
    $stats = [];
    foreach ($horses as $horse) {
        $stats[(string)$horse['id']] = [
            'ridesCount' => 0,
            'totalDurationMs' => 0,
            'totalDistanceM' => 0.0,
            'avgSpeedWeighted' => 0.0,
            'avgSpeedTimeMs' => 0,
            'maxSpeedMps' => 0.0,
            'lastRideMs' => 0,
        ];
    }

    foreach ($entries as $name => $data) {
        if (!preg_match('/^rides\/.+\.meta\.json$/', (string)$name)) {
            continue;
        }
        $meta = json_decode((string)$data, true);
        if (!is_array($meta)) {
            continue;
        }
        $horseId = (string)($meta['horseId'] ?? '');
        if ($horseId === '') {
            continue;
        }
        if (!isset($stats[$horseId])) {
            $stats[$horseId] = [
                'ridesCount' => 0,
                'totalDurationMs' => 0,
                'totalDistanceM' => 0.0,
                'avgSpeedWeighted' => 0.0,
                'avgSpeedTimeMs' => 0,
                'maxSpeedMps' => 0.0,
                'lastRideMs' => 0,
            ];
        }

        $start = (int)($meta['startTimeMs'] ?? 0);
        $end = (int)($meta['endTimeMs'] ?? $start);
        $duration = max(0, $end - $start);
        $distance = (float)($meta['distanceM'] ?? 0.0);
        $avgSpeed = (float)($meta['avgSpeedMps'] ?? 0.0);
        $maxSpeed = (float)($meta['maxSpeedMps'] ?? 0.0);

        $stats[$horseId]['ridesCount']++;
        $stats[$horseId]['totalDurationMs'] += $duration;
        $stats[$horseId]['totalDistanceM'] += $distance;
        if ($duration > 0) {
            $stats[$horseId]['avgSpeedWeighted'] += $avgSpeed * $duration;
            $stats[$horseId]['avgSpeedTimeMs'] += $duration;
        }
        $stats[$horseId]['maxSpeedMps'] = max($stats[$horseId]['maxSpeedMps'], $maxSpeed);
        $stats[$horseId]['lastRideMs'] = max($stats[$horseId]['lastRideMs'], $end);
    }

    foreach ($stats as $horseId => $row) {
        $time = (int)$row['avgSpeedTimeMs'];
        $stats[$horseId]['avgSpeedMps'] = $time > 0 ? (float)$row['avgSpeedWeighted'] / $time : 0.0;
        unset($stats[$horseId]['avgSpeedWeighted'], $stats[$horseId]['avgSpeedTimeMs']);
    }

    return $stats;
}

function formatDurationMs(int $ms): string
{
    $seconds = max(0, intdiv($ms, 1000));
    $hours = intdiv($seconds, 3600);
    $minutes = intdiv($seconds % 3600, 60);
    $sec = $seconds % 60;
    return sprintf('%d:%02d:%02d', $hours, $minutes, $sec);
}

function formatDateTimeMs(int $ms): string
{
    if ($ms <= 0) {
        return '-';
    }
    return date('Y-m-d H:i', (int)floor($ms / 1000));
}

function buildRideBaseName(int $startTimeMs, string $horseName): string
{
    return date('Y_m_d', (int)floor($startTimeMs / 1000)) . ' ' . sanitizeFileName($horseName);
}

function uniqueRideBaseName(array $entries, string $baseName): string
{
    if (!isset($entries['rides/' . $baseName . '.gpx']) && !isset($entries['rides/' . $baseName . '.meta.json'])) {
        return $baseName;
    }

    $suffix = date('His');
    $withTime = $baseName . ' ' . $suffix;
    if (!isset($entries['rides/' . $withTime . '.gpx']) && !isset($entries['rides/' . $withTime . '.meta.json'])) {
        return $withTime;
    }

    $i = 2;
    while (true) {
        $candidate = $withTime . ' ' . $i;
        if (!isset($entries['rides/' . $candidate . '.gpx']) && !isset($entries['rides/' . $candidate . '.meta.json'])) {
            return $candidate;
        }
        $i++;
    }
}

function sanitizeFileName(string $name): string
{
    $ascii = function_exists('iconv') ? @iconv('UTF-8', 'ASCII//TRANSLIT//IGNORE', $name) : false;
    $cleaned = preg_replace('/[^A-Za-z0-9 ]+/', ' ', $ascii !== false ? $ascii : $name);
    $cleaned = preg_replace('/\s+/', ' ', trim((string)$cleaned));
    return $cleaned === '' ? 'UnknownHorse' : $cleaned;
}

function sanitizeZipPath(string $path): ?string
{
    $normalized = trim(str_replace('\\', '/', $path), '/');
    if ($normalized === '' || $normalized === '..') {
        return null;
    }
    if (substr($normalized, 0, 3) === '../' || strpos($normalized, '/../') !== false || strpos($normalized, ':/') !== false) {
        return null;
    }
    return $normalized;
}

function uuidV4(): string
{
    $data = random_bytes(16);
    $data[6] = chr((ord($data[6]) & 0x0f) | 0x40);
    $data[8] = chr((ord($data[8]) & 0x3f) | 0x80);
    return vsprintf('%s%s-%s-%s-%s-%s%s%s', str_split(bin2hex($data), 4));
}

function renderLoginPage(string $error = ''): void
{
    header('Content-Type: text/html; charset=utf-8');
    $errorHtml = $error === '' ? '' : '<p class="error">' . htmlspecialchars($error, ENT_QUOTES, 'UTF-8') . '</p>';
    echo '<!doctype html><html lang="cs"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">';
    echo '<title>Horse Tracker GPX import</title>' . pageStyles() . '</head><body><main>';
    echo '<h1>Horse Tracker</h1><h2>GPX import</h2>' . $errorHtml;
    echo '<form method="get"><label>Bearer token<input type="password" name="token" required autofocus></label><button type="submit">Otevrit import</button></form>';
    echo '</main></body></html>';
    exit;
}

function renderImportPage(string $backupFile, string $requestToken, string $message = '', bool $isError = false): void
{
    $entries = [];
    $horses = [];
    $ridesCount = 0;
    $stats = [];
    $loadError = '';
    try {
        $entries = loadBackupEntries($backupFile);
        ensureBackupDefaults($entries);
        $horses = readHorses($entries);
        $stats = computeHorseStats($entries, $horses);
        foreach ($entries as $name => $_) {
            if (preg_match('/^rides\/.+\.meta\.json$/', (string)$name)) {
                $ridesCount++;
            }
        }
    } catch (Throwable $e) {
        $loadError = $e->getMessage();
    }

    header('Content-Type: text/html; charset=utf-8');
    echo '<!doctype html><html lang="cs"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">';
    echo '<title>Horse Tracker GPX import</title>' . pageStyles() . '</head><body><main>';
    echo '<h1>Horse Tracker</h1><h2>Import GPX trasy</h2>';

    if ($message !== '') {
        echo '<p class="' . ($isError ? 'error' : 'success') . '">' . htmlspecialchars($message, ENT_QUOTES, 'UTF-8') . '</p>';
    }
    if ($loadError !== '') {
        echo '<p class="error">' . htmlspecialchars($loadError, ENT_QUOTES, 'UTF-8') . '</p>';
    }

    echo '<p class="muted">V backupu je ' . count($horses) . ' koni a ' . $ridesCount . ' jizd. Po importu v Android aplikaci spust Obnovit z cloudu.</p>';
    renderStatsSection($horses, $stats);
    echo '<form method="post" enctype="multipart/form-data">';
    echo '<input type="hidden" name="token" value="' . htmlspecialchars($requestToken, ENT_QUOTES, 'UTF-8') . '">';
    echo '<label>Kun<select name="horseId">';
    foreach ($horses as $horse) {
        echo '<option value="' . htmlspecialchars((string)$horse['id'], ENT_QUOTES, 'UTF-8') . '">' . htmlspecialchars((string)$horse['name'], ENT_QUOTES, 'UTF-8') . '</option>';
    }
    echo '</select></label>';
    echo '<label>Novy kun<input type="text" name="newHorseName" placeholder="Vypln jen kdyz kun v seznamu neni"></label>';
    echo '<label>GPX soubor<input type="file" name="gpx" accept=".gpx,application/gpx+xml,text/xml,application/xml" required></label>';
    echo '<button type="submit">Importovat GPX</button>';
    echo '</form>';
    echo '<p><a href="?download=1&amp;token=' . rawurlencode($requestToken) . '">Stahnout aktualni backup ZIP</a></p>';
    echo '</main></body></html>';
    exit;
}

function renderStatsSection(array $horses, array $stats): void
{
    echo '<section class="stats"><h3>Statistiky koni</h3>';
    if ($horses === []) {
        echo '<p class="muted">Zatim tu nejsou zadni kone.</p></section>';
        return;
    }

    echo '<div class="stats-grid">';
    foreach ($horses as $horse) {
        $horseId = (string)$horse['id'];
        $row = $stats[$horseId] ?? [
            'ridesCount' => 0,
            'totalDurationMs' => 0,
            'totalDistanceM' => 0.0,
            'avgSpeedMps' => 0.0,
            'maxSpeedMps' => 0.0,
            'lastRideMs' => 0,
        ];
        echo '<article class="horse-stat">';
        echo '<h4>' . htmlspecialchars((string)$horse['name'], ENT_QUOTES, 'UTF-8') . '</h4>';
        echo '<dl>';
        echo '<div><dt>Jizdy</dt><dd>' . (int)$row['ridesCount'] . '</dd></div>';
        echo '<div><dt>Cas</dt><dd>' . htmlspecialchars(formatDurationMs((int)$row['totalDurationMs']), ENT_QUOTES, 'UTF-8') . '</dd></div>';
        echo '<div><dt>Vzdalenost</dt><dd>' . number_format((float)$row['totalDistanceM'] / 1000.0, 1, ',', ' ') . ' km</dd></div>';
        echo '<div><dt>Prumer</dt><dd>' . number_format((float)$row['avgSpeedMps'] * 3.6, 1, ',', ' ') . ' km/h</dd></div>';
        echo '<div><dt>Maximum</dt><dd>' . number_format((float)$row['maxSpeedMps'] * 3.6, 1, ',', ' ') . ' km/h</dd></div>';
        echo '<div><dt>Posledni</dt><dd>' . htmlspecialchars(formatDateTimeMs((int)$row['lastRideMs']), ENT_QUOTES, 'UTF-8') . '</dd></div>';
        echo '</dl></article>';
    }
    echo '</div></section>';
}

function pageStyles(): string
{
    return '<style>
        :root{color-scheme:light;font-family:system-ui,-apple-system,Segoe UI,sans-serif;color:#17212b;background:#f4f6f8}
        body{margin:0;padding:24px}
        main{max-width:860px;margin:0 auto;background:#fff;border:1px solid #d8e0e8;border-radius:8px;padding:24px;box-shadow:0 10px 30px rgba(23,33,43,.08)}
        h1{margin:0 0 4px;font-size:28px}
        h2{margin:0 0 20px;font-size:18px;font-weight:600;color:#3c4b57}
        h3{margin:24px 0 12px;font-size:18px}
        h4{margin:0 0 10px;font-size:16px}
        form{display:grid;gap:14px}
        label{display:grid;gap:6px;font-weight:600}
        input,select{font:inherit;padding:10px;border:1px solid #b8c4ce;border-radius:6px;background:#fff}
        button{font:inherit;font-weight:700;padding:11px 14px;border:0;border-radius:6px;background:#1769aa;color:#fff}
        .stats{margin:20px 0 24px}
        .stats-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:12px}
        .horse-stat{border:1px solid #d8e0e8;border-radius:8px;padding:14px;background:#fbfcfd}
        dl{display:grid;gap:7px;margin:0}
        dl div{display:flex;justify-content:space-between;gap:12px;border-top:1px solid #e6edf3;padding-top:7px}
        dl div:first-child{border-top:0;padding-top:0}
        dt{color:#5c6b76}
        dd{margin:0;font-weight:700;text-align:right}
        .muted{color:#5c6b76}
        .success{padding:10px 12px;border-radius:6px;background:#e5f6ec;color:#126b35}
        .error{padding:10px 12px;border-radius:6px;background:#fdecea;color:#9f1c14}
        a{color:#1769aa}
    </style>';
}

function respond(int $status, string $message): void
{
    http_response_code($status);
    header('Content-Type: text/plain; charset=utf-8');
    echo $message;
    exit;
}

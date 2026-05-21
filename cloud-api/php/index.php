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
$backupHistoryDir = (string)($config['backup_history_dir'] ?? (rtrim($storageDir, "/\\") . DIRECTORY_SEPARATOR . 'history'));
$maxBackupVersions = (int)($config['max_backup_versions'] ?? 30);
$rejectEmptyOverwrite = (bool)($config['reject_empty_overwrite'] ?? true);
$backupFile = rtrim($storageDir, "/\\") . DIRECTORY_SEPARATOR . 'horse_tracker_backup.zip';

if ($token === '' || $token === 'CHANGE_ME_TO_A_LONG_RANDOM_TOKEN') {
    respond(500, 'Server token is not configured.');
}

$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';

if ($method === 'GET' && wantsHtml() && !isset($_GET['download'])) {
    if (!isAuthorized($token)) {
        renderLoginPage();
    }
    renderImportPage($backupFile, tokenFromRequest(), '', false, $backupHistoryDir);
}

if ($method === 'GET' && isset($_GET['download'])) {
    if (!isAuthorized($token)) {
        renderLoginPage('Neplatny token.');
    }
    sendBackup($backupFile);
}

if ($method === 'GET' && isset($_GET['history'])) {
    if (!isAuthorized($token)) {
        renderLoginPage('Neplatny token.');
    }
    sendHistoryBackup($backupHistoryDir, (string)$_GET['history']);
}

if ($method === 'POST') {
    if (!isAuthorized($token)) {
        renderLoginPage('Neplatny token.');
    }
    handleGpxImport($backupFile, tokenFromRequest(), $maxGpxUploadBytes, $backupHistoryDir, $maxBackupVersions);
}

requireBearerToken($token);

if ($method === 'PUT') {
    saveBackup($backupFile, $maxUploadBytes, $backupHistoryDir, $maxBackupVersions, $rejectEmptyOverwrite);
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

function saveBackup(
    string $backupFile,
    int $maxUploadBytes,
    string $backupHistoryDir,
    int $maxBackupVersions,
    bool $rejectEmptyOverwrite
): void
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
        if (is_resource($input)) {
            fclose($input);
        }
        if (is_resource($output)) {
            fclose($output);
        }
        flock($lockHandle, LOCK_UN);
        fclose($lockHandle);
        respond(500, 'Cannot open upload streams');
    }

    $bytes = 0;
    while (!feof($input)) {
        $chunk = fread($input, 1024 * 1024);
        if ($chunk === false) {
            @unlink($tempFile);
            fclose($input);
            fclose($output);
            flock($lockHandle, LOCK_UN);
            fclose($lockHandle);
            respond(500, 'Cannot read upload');
        }
        $bytes += strlen($chunk);
        if ($bytes > $maxUploadBytes) {
            @unlink($tempFile);
            fclose($input);
            fclose($output);
            flock($lockHandle, LOCK_UN);
            fclose($lockHandle);
            respond(413, 'Backup is too large');
        }
        fwrite($output, $chunk);
    }

    fclose($input);
    fclose($output);

    if ($bytes === 0) {
        @unlink($tempFile);
        flock($lockHandle, LOCK_UN);
        fclose($lockHandle);
        respond(400, 'Empty backup upload');
    }

    try {
        guardUploadedBackup($tempFile, $backupFile, $rejectEmptyOverwrite);
        snapshotExistingBackup($backupFile, $backupHistoryDir, $maxBackupVersions);
    } catch (Throwable $e) {
        @unlink($tempFile);
        flock($lockHandle, LOCK_UN);
        fclose($lockHandle);
        respond(409, $e->getMessage());
    }

    if (!rename($tempFile, $backupFile)) {
        @unlink($tempFile);
        flock($lockHandle, LOCK_UN);
        fclose($lockHandle);
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

function sendHistoryBackup(string $backupHistoryDir, string $fileName): void
{
    $baseName = basename($fileName);
    if ($baseName !== $fileName || !preg_match('/^horse_tracker_backup_\d{8}_\d{6}_[a-f0-9]{8}\.zip$/', $baseName)) {
        respond(400, 'Invalid history backup name');
    }

    $historyFile = rtrim($backupHistoryDir, "/\\") . DIRECTORY_SEPARATOR . $baseName;
    if (!is_file($historyFile)) {
        respond(404, 'History backup not found');
    }

    header('Content-Type: application/zip');
    header('Content-Disposition: attachment; filename="' . $baseName . '"');
    header('Content-Length: ' . filesize($historyFile));
    readfile($historyFile);
    exit;
}

function guardUploadedBackup(string $uploadedFile, string $currentBackupFile, bool $rejectEmptyOverwrite): void
{
    if (!class_exists('ZipArchive')) {
        throw new RuntimeException('Na serveru chybi PHP rozsireni ZipArchive.');
    }

    $incoming = summarizeBackupFile($uploadedFile);
    if (!$incoming['hasMetadata']) {
        throw new RuntimeException('Nahrany ZIP neni platny Horse Tracker backup.');
    }

    if (!$rejectEmptyOverwrite || !is_file($currentBackupFile)) {
        return;
    }

    $current = summarizeBackupFile($currentBackupFile);
    $currentHasData = $current['horsesCount'] > 0 || $current['ridesCount'] > 0;
    $incomingIsEmpty = $incoming['horsesCount'] === 0 && $incoming['ridesCount'] === 0;
    if ($currentHasData && $incomingIsEmpty) {
        throw new RuntimeException(
            'Odmitnuto: prazdny backup by prepsal cloud, kde uz jsou ' .
            $current['horsesCount'] . ' kone a ' . $current['ridesCount'] . ' jizdy.'
        );
    }
}

function summarizeBackupFile(string $backupFile): array
{
    if (!is_file($backupFile)) {
        return [
            'hasMetadata' => false,
            'horsesCount' => 0,
            'ridesCount' => 0,
            'bytes' => 0,
        ];
    }

    $entries = loadBackupEntries($backupFile);
    return summarizeBackupEntries($entries) + ['bytes' => filesize($backupFile)];
}

function summarizeBackupEntries(array $entries): array
{
    $ridesCount = 0;
    foreach ($entries as $name => $_) {
        if (preg_match('/^rides\/.+\.meta\.json$/', (string)$name)) {
            $ridesCount++;
        }
    }

    return [
        'hasMetadata' => isset($entries['backup.json']),
        'horsesCount' => count(readHorses($entries)),
        'ridesCount' => $ridesCount,
    ];
}

function snapshotExistingBackup(string $backupFile, string $backupHistoryDir, int $maxBackupVersions): void
{
    if ($maxBackupVersions <= 0 || !is_file($backupFile) || filesize($backupFile) <= 0) {
        return;
    }

    if (!is_dir($backupHistoryDir) && !mkdir($backupHistoryDir, 0700, true) && !is_dir($backupHistoryDir)) {
        throw new RuntimeException('Nelze vytvorit adresar historickych zaloh.');
    }

    $hash = @sha1_file($backupFile);
    $hashPart = is_string($hash) ? substr($hash, 0, 8) : substr(bin2hex(random_bytes(4)), 0, 8);
    $target =
        rtrim($backupHistoryDir, "/\\") .
        DIRECTORY_SEPARATOR .
        'horse_tracker_backup_' .
        gmdate('Ymd_His') .
        '_' .
        $hashPart .
        '.zip';

    if (!copy($backupFile, $target)) {
        throw new RuntimeException('Nelze vytvorit historickou zalohu pred prepsanim.');
    }
    @chmod($target, 0600);
    pruneBackupHistory($backupHistoryDir, $maxBackupVersions);
}

function listHistoryBackups(string $backupHistoryDir): array
{
    if (!is_dir($backupHistoryDir)) {
        return [];
    }

    $files = glob(rtrim($backupHistoryDir, "/\\") . DIRECTORY_SEPARATOR . 'horse_tracker_backup_*.zip') ?: [];
    usort($files, static function (string $a, string $b): int {
        return filemtime($b) <=> filemtime($a);
    });

    return $files;
}

function pruneBackupHistory(string $backupHistoryDir, int $maxBackupVersions): void
{
    $files = listHistoryBackups($backupHistoryDir);
    foreach (array_slice($files, $maxBackupVersions) as $oldFile) {
        @unlink($oldFile);
    }
}

function handleGpxImport(
    string $backupFile,
    string $requestToken,
    int $maxGpxUploadBytes,
    string $backupHistoryDir,
    int $maxBackupVersions
): void
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
        saveBackupEntries($backupFile, $entries, $backupHistoryDir, $maxBackupVersions);

        $message =
            'Import hotov: ' . $horseName .
            ', ' . date('Y-m-d H:i', (int)floor($stats['startTimeMs'] / 1000)) .
            ', ' . number_format($stats['distanceM'] / 1000, 2, ',', ' ') . ' km.';
        renderImportPage($backupFile, $requestToken, $message, false, $backupHistoryDir);
    } catch (Throwable $e) {
        renderImportPage($backupFile, $requestToken, $e->getMessage(), true, $backupHistoryDir);
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

function saveBackupEntries(string $backupFile, array $entries, string $backupHistoryDir, int $maxBackupVersions): void
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

    try {
        snapshotExistingBackup($backupFile, $backupHistoryDir, $maxBackupVersions);

        if (!rename($tempFile, $backupFile)) {
            @unlink($tempFile);
            throw new RuntimeException('Nelze ulozit backup.');
        }

        @chmod($backupFile, 0600);
    } finally {
        if (is_file($tempFile)) {
            @unlink($tempFile);
        }
        flock($lockHandle, LOCK_UN);
        fclose($lockHandle);
    }
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
            'longestDistanceM' => 0.0,
            'longestDurationMs' => 0,
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
                'longestDistanceM' => 0.0,
                'longestDurationMs' => 0,
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
        $stats[$horseId]['longestDistanceM'] = max($stats[$horseId]['longestDistanceM'], $distance);
        $stats[$horseId]['longestDurationMs'] = max($stats[$horseId]['longestDurationMs'], $duration);
        $stats[$horseId]['lastRideMs'] = max($stats[$horseId]['lastRideMs'], $end);
    }

    foreach ($stats as $horseId => $row) {
        $time = (int)$row['avgSpeedTimeMs'];
        $stats[$horseId]['avgSpeedMps'] = $time > 0 ? (float)$row['avgSpeedWeighted'] / $time : 0.0;
        unset($stats[$horseId]['avgSpeedWeighted'], $stats[$horseId]['avgSpeedTimeMs']);
    }

    return $stats;
}

function collectRideSummaries(array $entries, array $horses): array
{
    $horseNames = [];
    foreach ($horses as $horse) {
        $horseNames[(string)$horse['id']] = (string)$horse['name'];
    }

    $rides = [];
    foreach ($entries as $name => $data) {
        if (!preg_match('/^rides\/.+\.meta\.json$/', (string)$name)) {
            continue;
        }
        $meta = json_decode((string)$data, true);
        if (!is_array($meta)) {
            continue;
        }

        $horseId = (string)($meta['horseId'] ?? '');
        $start = (int)($meta['startTimeMs'] ?? 0);
        $end = (int)($meta['endTimeMs'] ?? $start);
        if ($start <= 0 || $end <= 0) {
            continue;
        }

        $rides[] = [
            'horseId' => $horseId,
            'horseName' => $horseNames[$horseId] ?? 'Neznamy kun',
            'startTimeMs' => $start,
            'endTimeMs' => $end,
            'durationMs' => max(0, $end - $start),
            'distanceM' => max(0.0, (float)($meta['distanceM'] ?? 0.0)),
            'avgSpeedMps' => max(0.0, (float)($meta['avgSpeedMps'] ?? 0.0)),
            'maxSpeedMps' => max(0.0, (float)($meta['maxSpeedMps'] ?? 0.0)),
        ];
    }

    usort($rides, static function (array $a, array $b): int {
        return (int)$b['startTimeMs'] <=> (int)$a['startTimeMs'];
    });

    return $rides;
}

function computeOverviewStats(array $rides): array
{
    $overview = [
        'ridesCount' => count($rides),
        'totalDurationMs' => 0,
        'totalDistanceM' => 0.0,
        'maxSpeedMps' => 0.0,
        'lastRideMs' => 0,
        'longestDistanceM' => 0.0,
        'longestDurationMs' => 0,
    ];

    foreach ($rides as $ride) {
        $duration = (int)$ride['durationMs'];
        $distance = (float)$ride['distanceM'];
        $overview['totalDurationMs'] += $duration;
        $overview['totalDistanceM'] += $distance;
        $overview['maxSpeedMps'] = max($overview['maxSpeedMps'], (float)$ride['maxSpeedMps']);
        $overview['lastRideMs'] = max($overview['lastRideMs'], (int)$ride['endTimeMs']);
        $overview['longestDistanceM'] = max($overview['longestDistanceM'], $distance);
        $overview['longestDurationMs'] = max($overview['longestDurationMs'], $duration);
    }

    $durationS = max(1.0, $overview['totalDurationMs'] / 1000.0);
    $overview['avgSpeedMps'] = $overview['totalDistanceM'] / $durationS;

    return $overview;
}

function computeDailyActivity(array $rides, int $days): array
{
    if ($rides === []) {
        return [];
    }

    $lastMs = 0;
    foreach ($rides as $ride) {
        $lastMs = max($lastMs, (int)$ride['startTimeMs']);
    }

    $lastDay = strtotime(date('Y-m-d 00:00:00', (int)floor($lastMs / 1000)));
    if ($lastDay === false) {
        return [];
    }
    $firstDay = strtotime('-' . max(0, $days - 1) . ' days', $lastDay);
    if ($firstDay === false) {
        return [];
    }

    $daily = [];
    for ($time = $firstDay; $time <= $lastDay; $time += 86400) {
        $key = date('Y-m-d', $time);
        $daily[$key] = [
            'label' => date('j.n.', $time),
            'ridesCount' => 0,
            'distanceM' => 0.0,
            'durationMs' => 0,
        ];
    }

    foreach ($rides as $ride) {
        $day = date('Y-m-d', (int)floor((int)$ride['startTimeMs'] / 1000));
        if (!isset($daily[$day])) {
            continue;
        }
        $daily[$day]['ridesCount']++;
        $daily[$day]['distanceM'] += (float)$ride['distanceM'];
        $daily[$day]['durationMs'] += (int)$ride['durationMs'];
    }

    return array_values($daily);
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

function renderImportPage(
    string $backupFile,
    string $requestToken,
    string $message = '',
    bool $isError = false,
    string $backupHistoryDir = ''
): void
{
    $entries = [];
    $horses = [];
    $ridesCount = 0;
    $stats = [];
    $rides = [];
    $overview = computeOverviewStats([]);
    $dailyActivity = [];
    $loadError = '';
    try {
        $entries = loadBackupEntries($backupFile);
        ensureBackupDefaults($entries);
        $horses = readHorses($entries);
        $stats = computeHorseStats($entries, $horses);
        $rides = collectRideSummaries($entries, $horses);
        $overview = computeOverviewStats($rides);
        $dailyActivity = computeDailyActivity($rides, 14);
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
    renderOverviewSection(count($horses), $overview);
    renderStatsSection($horses, $stats);
    renderChartsSection($horses, $stats, $dailyActivity);
    renderRecentRidesSection($rides);
    renderBackupHistorySection($backupHistoryDir, $requestToken);
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

function renderOverviewSection(int $horsesCount, array $overview): void
{
    echo '<section class="overview" aria-label="Souhrn">';
    echo '<div class="overview-card"><span>Kone</span><strong>' . $horsesCount . '</strong></div>';
    echo '<div class="overview-card"><span>Jizdy</span><strong>' . (int)$overview['ridesCount'] . '</strong></div>';
    echo '<div class="overview-card"><span>Vzdalenost</span><strong>' . formatDistanceKm((float)$overview['totalDistanceM']) . '</strong></div>';
    echo '<div class="overview-card"><span>Cas</span><strong>' . htmlspecialchars(formatDurationMs((int)$overview['totalDurationMs']), ENT_QUOTES, 'UTF-8') . '</strong></div>';
    echo '<div class="overview-card"><span>Prumer</span><strong>' . formatSpeedKmh((float)$overview['avgSpeedMps']) . '</strong></div>';
    echo '<div class="overview-card"><span>Maximum</span><strong>' . formatSpeedKmh((float)$overview['maxSpeedMps']) . '</strong></div>';
    echo '<div class="overview-card"><span>Nejdelsi trasa</span><strong>' . formatDistanceKm((float)$overview['longestDistanceM']) . '</strong></div>';
    echo '<div class="overview-card"><span>Posledni</span><strong>' . htmlspecialchars(formatDateTimeMs((int)$overview['lastRideMs']), ENT_QUOTES, 'UTF-8') . '</strong></div>';
    echo '</section>';
}

function renderBackupHistorySection(string $backupHistoryDir, string $requestToken): void
{
    if ($backupHistoryDir === '') {
        return;
    }

    $files = array_slice(listHistoryBackups($backupHistoryDir), 0, 10);
    echo '<section class="history"><h3>Historicke zalohy</h3>';
    if ($files === []) {
        echo '<p class="muted">Zatim neni ulozena zadna starsi cloud zaloha.</p></section>';
        return;
    }

    echo '<ul class="history-list">';
    foreach ($files as $file) {
        $baseName = basename($file);
        $time = date('Y-m-d H:i:s', filemtime($file));
        $size = number_format((float)filesize($file) / 1024.0, 1, ',', ' ');
        echo '<li><a href="?history=' . rawurlencode($baseName) . '&amp;token=' . rawurlencode($requestToken) . '">' .
            htmlspecialchars($time, ENT_QUOTES, 'UTF-8') .
            '</a> <span class="muted">' .
            htmlspecialchars($size, ENT_QUOTES, 'UTF-8') .
            ' KB</span></li>';
    }
    echo '</ul></section>';
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
            'longestDistanceM' => 0.0,
            'longestDurationMs' => 0,
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
        echo '<div><dt>Nejdelsi</dt><dd>' . formatDistanceKm((float)$row['longestDistanceM']) . '</dd></div>';
        echo '<div><dt>Posledni</dt><dd>' . htmlspecialchars(formatDateTimeMs((int)$row['lastRideMs']), ENT_QUOTES, 'UTF-8') . '</dd></div>';
        echo '</dl></article>';
    }
    echo '</div></section>';
}

function renderChartsSection(array $horses, array $stats, array $dailyActivity): void
{
    if ($horses === [] && $dailyActivity === []) {
        return;
    }

    $maxDistance = 0.0;
    $maxRides = 0;
    foreach ($horses as $horse) {
        $row = $stats[(string)$horse['id']] ?? [];
        $maxDistance = max($maxDistance, (float)($row['totalDistanceM'] ?? 0.0));
        $maxRides = max($maxRides, (int)($row['ridesCount'] ?? 0));
    }

    echo '<section class="charts"><h3>Grafy</h3><div class="chart-grid">';
    renderHorseBarChart('Vzdalenost podle kone', $horses, $stats, 'totalDistanceM', $maxDistance, static function (float $value): string {
        return formatDistanceKm($value);
    });
    renderHorseBarChart('Pocet jizd podle kone', $horses, $stats, 'ridesCount', (float)$maxRides, static function (float $value): string {
        return (string)(int)$value;
    });
    echo '</div>';

    renderDailyActivityChart($dailyActivity);
    echo '</section>';
}

function renderHorseBarChart(string $title, array $horses, array $stats, string $field, float $maxValue, callable $formatter): void
{
    echo '<article class="chart-panel"><h4>' . htmlspecialchars($title, ENT_QUOTES, 'UTF-8') . '</h4>';
    if ($horses === []) {
        echo '<p class="muted">Zatim nejsou data pro graf.</p></article>';
        return;
    }

    $sortedHorses = $horses;
    usort($sortedHorses, static function (array $a, array $b) use ($stats, $field): int {
        $aValue = (float)($stats[(string)$a['id']][$field] ?? 0.0);
        $bValue = (float)($stats[(string)$b['id']][$field] ?? 0.0);
        if ($aValue === $bValue) {
            return strnatcasecmp((string)$a['name'], (string)$b['name']);
        }
        return $bValue <=> $aValue;
    });

    echo '<div class="bar-chart">';
    foreach ($sortedHorses as $horse) {
        $row = $stats[(string)$horse['id']] ?? [];
        $value = (float)($row[$field] ?? 0.0);
        $percent = $maxValue > 0.0 ? max(2.0, min(100.0, ($value / $maxValue) * 100.0)) : 0.0;
        echo '<div class="bar-row">';
        echo '<span class="bar-label">' . htmlspecialchars((string)$horse['name'], ENT_QUOTES, 'UTF-8') . '</span>';
        echo '<span class="bar-track"><span class="bar-fill" style="width:' . chartPercent($percent) . '%"></span></span>';
        echo '<strong>' . htmlspecialchars($formatter($value), ENT_QUOTES, 'UTF-8') . '</strong>';
        echo '</div>';
    }
    echo '</div></article>';
}

function renderDailyActivityChart(array $dailyActivity): void
{
    if ($dailyActivity === []) {
        return;
    }

    $maxDistance = 0.0;
    foreach ($dailyActivity as $day) {
        $maxDistance = max($maxDistance, (float)$day['distanceM']);
    }

    echo '<article class="activity-panel"><h4>Aktivita po dnech</h4><div class="day-chart">';
    foreach ($dailyActivity as $day) {
        $distance = (float)$day['distanceM'];
        $percent = $maxDistance > 0.0 && $distance > 0.0 ? max(8.0, min(100.0, ($distance / $maxDistance) * 100.0)) : 0.0;
        $title =
            (string)$day['label'] . ': ' .
            (int)$day['ridesCount'] . ' jizd, ' .
            formatDistanceKm($distance) . ', ' .
            formatDurationMs((int)$day['durationMs']);
        echo '<div class="day-column" title="' . htmlspecialchars($title, ENT_QUOTES, 'UTF-8') . '">';
        echo '<span class="day-bar-wrap"><span class="day-bar" style="height:' . chartPercent($percent) . '%"></span></span>';
        echo '<span class="day-label">' . htmlspecialchars((string)$day['label'], ENT_QUOTES, 'UTF-8') . '</span>';
        echo '</div>';
    }
    echo '</div></article>';
}

function renderRecentRidesSection(array $rides): void
{
    echo '<section class="recent-rides"><h3>Posledni jizdy</h3>';
    if ($rides === []) {
        echo '<p class="muted">Zatim tu nejsou zadne jizdy.</p></section>';
        return;
    }

    echo '<div class="table-wrap"><table><thead><tr>';
    echo '<th>Datum</th><th>Kun</th><th>Vzdalenost</th><th>Cas</th><th>Prumer</th><th>Maximum</th>';
    echo '</tr></thead><tbody>';
    foreach (array_slice($rides, 0, 8) as $ride) {
        echo '<tr>';
        echo '<td>' . htmlspecialchars(formatDateTimeMs((int)$ride['startTimeMs']), ENT_QUOTES, 'UTF-8') . '</td>';
        echo '<td>' . htmlspecialchars((string)$ride['horseName'], ENT_QUOTES, 'UTF-8') . '</td>';
        echo '<td>' . formatDistanceKm((float)$ride['distanceM']) . '</td>';
        echo '<td>' . htmlspecialchars(formatDurationMs((int)$ride['durationMs']), ENT_QUOTES, 'UTF-8') . '</td>';
        echo '<td>' . formatSpeedKmh((float)$ride['avgSpeedMps']) . '</td>';
        echo '<td>' . formatSpeedKmh((float)$ride['maxSpeedMps']) . '</td>';
        echo '</tr>';
    }
    echo '</tbody></table></div></section>';
}

function formatDistanceKm(float $meters): string
{
    return number_format($meters / 1000.0, 1, ',', ' ') . ' km';
}

function formatSpeedKmh(float $metersPerSecond): string
{
    return number_format($metersPerSecond * 3.6, 1, ',', ' ') . ' km/h';
}

function chartPercent(float $value): string
{
    return number_format(max(0.0, min(100.0, $value)), 2, '.', '');
}

function pageStyles(): string
{
    return '<style>
        :root{color-scheme:light;font-family:system-ui,-apple-system,Segoe UI,sans-serif;color:#17212b;background:#f4f6f8}
        body{margin:0;padding:24px}
        main{max-width:1080px;margin:0 auto;background:#fff;border:1px solid #d8e0e8;border-radius:8px;padding:24px;box-shadow:0 10px 30px rgba(23,33,43,.08)}
        h1{margin:0 0 4px;font-size:28px}
        h2{margin:0 0 20px;font-size:18px;font-weight:600;color:#3c4b57}
        h3{margin:24px 0 12px;font-size:18px}
        h4{margin:0 0 10px;font-size:16px}
        form{display:grid;gap:14px}
        label{display:grid;gap:6px;font-weight:600}
        input,select{font:inherit;padding:10px;border:1px solid #b8c4ce;border-radius:6px;background:#fff}
        button{font:inherit;font-weight:700;padding:11px 14px;border:0;border-radius:6px;background:#1769aa;color:#fff}
        .overview{display:grid;grid-template-columns:repeat(auto-fit,minmax(145px,1fr));gap:10px;margin:18px 0 24px}
        .overview-card{border:1px solid #d8e0e8;border-radius:8px;background:#fbfcfd;padding:12px}
        .overview-card span{display:block;color:#5c6b76;font-size:13px}
        .overview-card strong{display:block;margin-top:4px;font-size:19px;line-height:1.2}
        .stats{margin:20px 0 24px}
        .stats-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:12px}
        .horse-stat{border:1px solid #d8e0e8;border-radius:8px;padding:14px;background:#fbfcfd}
        dl{display:grid;gap:7px;margin:0}
        dl div{display:flex;justify-content:space-between;gap:12px;border-top:1px solid #e6edf3;padding-top:7px}
        dl div:first-child{border-top:0;padding-top:0}
        dt{color:#5c6b76}
        dd{margin:0;font-weight:700;text-align:right}
        .charts{margin:20px 0 24px}
        .chart-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:12px}
        .chart-panel,.activity-panel{border:1px solid #d8e0e8;border-radius:8px;background:#fbfcfd;padding:14px}
        .bar-chart{display:grid;gap:10px}
        .bar-row{display:grid;grid-template-columns:minmax(74px,1fr) minmax(90px,2fr) auto;gap:10px;align-items:center}
        .bar-label{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#344654}
        .bar-track{height:12px;border-radius:999px;background:#e6edf3;overflow:hidden}
        .bar-fill{display:block;height:100%;border-radius:999px;background:#1769aa}
        .bar-row strong{font-size:14px;text-align:right;white-space:nowrap}
        .activity-panel{margin-top:12px}
        .day-chart{display:grid;grid-template-columns:repeat(14,minmax(30px,1fr));gap:8px;align-items:end;min-height:150px}
        .day-column{display:grid;grid-template-rows:110px auto;gap:7px;align-items:end;min-width:0}
        .day-bar-wrap{display:flex;align-items:end;height:110px;border-radius:6px;background:#e6edf3;overflow:hidden}
        .day-bar{display:block;width:100%;border-radius:6px 6px 0 0;background:#2b8a5f}
        .day-label{font-size:12px;color:#5c6b76;text-align:center;white-space:nowrap}
        .recent-rides{margin:20px 0 24px}
        .table-wrap{overflow-x:auto;border:1px solid #d8e0e8;border-radius:8px}
        table{width:100%;border-collapse:collapse;background:#fbfcfd}
        th,td{padding:10px 12px;border-bottom:1px solid #e6edf3;text-align:left;white-space:nowrap}
        th{font-size:13px;color:#5c6b76;background:#f1f5f8}
        tbody tr:last-child td{border-bottom:0}
        .history{margin:20px 0 24px}
        .history-list{display:grid;gap:7px;margin:0;padding-left:20px}
        .muted{color:#5c6b76}
        .success{padding:10px 12px;border-radius:6px;background:#e5f6ec;color:#126b35}
        .error{padding:10px 12px;border-radius:6px;background:#fdecea;color:#9f1c14}
        a{color:#1769aa}
        @media (max-width:700px){
            body{padding:12px}
            main{padding:16px}
            .overview{grid-template-columns:repeat(2,minmax(0,1fr))}
            .bar-row{grid-template-columns:1fr;gap:5px}
            .bar-row strong{text-align:left}
            .day-chart{grid-template-columns:repeat(7,minmax(28px,1fr));row-gap:14px}
        }
    </style>';
}

function respond(int $status, string $message): void
{
    http_response_code($status);
    header('Content-Type: text/plain; charset=utf-8');
    echo $message;
    exit;
}

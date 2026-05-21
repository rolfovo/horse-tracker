<?php

return [
    // Change this to a long random secret and use the same value in the app.
    // Example generator:
    //   openssl rand -hex 32
    'token' => 'asdf',

    // Keep this directory outside the public web root when your hosting allows it.
    // For simple shared hosting this local "data" folder also works; its .htaccess
    // blocks direct browser access on Apache.
    'storage_dir' => __DIR__ . '/data',

    // Existing cloud backup ZIP is copied here before every successful overwrite.
    'backup_history_dir' => __DIR__ . '/data/history',

    // Keep this many older cloud ZIP files.
    'max_backup_versions' => 30,

    // Refuse PUT uploads that would replace a non-empty cloud backup with an
    // empty backup.
    'reject_empty_overwrite' => true,

    // Safety limit for uploaded ZIP backup.
    'max_upload_bytes' => 50 * 1024 * 1024,

    // Safety limit for GPX files uploaded through the browser import page.
    'max_gpx_upload_bytes' => 10 * 1024 * 1024,
];

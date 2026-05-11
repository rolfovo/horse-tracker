<?php

return [
    // Change this to a long random secret and use the same value in the app.
    // Example generator:
    //   openssl rand -hex 32
    'token' => 'CHANGE_ME_TO_A_LONG_RANDOM_TOKEN',

    // Keep this directory outside the public web root when your hosting allows it.
    // For simple shared hosting this local "data" folder also works; its .htaccess
    // blocks direct browser access on Apache.
    'storage_dir' => __DIR__ . '/data',

    // Safety limit for uploaded ZIP backup.
    'max_upload_bytes' => 50 * 1024 * 1024,

    // Safety limit for GPX files uploaded through the browser import page.
    'max_gpx_upload_bytes' => 10 * 1024 * 1024,
];

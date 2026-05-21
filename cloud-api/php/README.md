# Horse Tracker Cloud API - PHP

Simple HTTP endpoint for the Android app cloud backup.

The app uses:

- `PUT` to upload one ZIP backup.
- `GET` to download the same ZIP backup.
- `Authorization: Bearer <token>` for authentication.

## Install

1. Upload this whole folder to your web hosting, for example:

   `https://zahradnice.cz/horse-tracker-cloud/`

2. On the server, copy:

   `config.example.php` -> `config.php`

3. Edit `config.php` and set the same token as in the Android app.

   Current configured token in the example is `asdf`.

4. Make sure PHP can write into the `data` directory.

   On Linux hosting:

   ```sh
   chmod 700 data
   ```

## App Settings

In the Android app fill:

Cloud API URL:

```text
https://zahradnice.cz/horse-tracker-cloud/
```

Bearer token:

```text
asdf
```

Then turn `Sync ON` and tap `Ulozit`.

## GPX Import From iPhone

Open the same Cloud API URL in Safari or Chrome on iPhone:

```text
https://zahradnice.cz/horse-tracker-cloud/
```

Enter the bearer token, choose the horse, pick a `.gpx` file, and submit the form.

The page also shows horse statistics from the cloud backup: ride count, total time, total distance, average speed, max speed, and last ride.

The server imports the GPX into the stored backup ZIP:

- track date/time comes from GPX `<trkpt><time>...</time>` values,
- distance, duration, average speed, max speed, and point count are calculated on upload,
- if the horse is not in the backup yet, fill `Novy kun` instead of choosing from the list.

After uploading GPX from the iPhone, open the Android app and tap `Obnovit` in Cloud sync to pull the updated backup.

## Test With Curl

Create a tiny test ZIP first, then upload it:

```sh
curl -i -X PUT \
  -H "Authorization: Bearer asdf" \
  -H "Content-Type: application/zip" \
  --data-binary @test.zip \
  https://zahradnice.cz/horse-tracker-cloud/
```

Download it back:

```sh
curl -i \
  -H "Authorization: Bearer asdf" \
  -o restored.zip \
  https://zahradnice.cz/horse-tracker-cloud/
```

Expected results:

- Upload returns `HTTP/1.1 200 OK`.
- Download returns `HTTP/1.1 200 OK` and creates `restored.zip`.

## Notes

- Use HTTPS.
- Keep `config.php` private.
- Before every overwrite, the server copies the previous ZIP into `data/history`.
- A PUT upload that would replace a non-empty cloud backup with an empty backup is rejected by default.
- If your server runs Nginx instead of Apache, `.htaccess` files are ignored. In that case, place `storage_dir` outside the public web root when possible.

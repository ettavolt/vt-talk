import {readFile} from 'node:fs/promises';
import {request} from 'undici';

const logProcess = false;

async function processFile(filePath: string, requestUrl: string) {
    const data = await readFile(filePath);
    if (logProcess) console.log(`Successfully read ${data.length} bytes from file`);

    if (logProcess) console.log(`Sending HTTP request to: ${requestUrl}`);
    const res = await request(requestUrl, {
        method: 'POST',
        headers: {
            'Content-Type': 'text/plain',
            'Content-Length': data.length.toString(),
        },
        bodyTimeout: 10000,
        headersTimeout: 10000,
        body: data,
    });
    if (logProcess) console.log(`Received response with status code: ${res.statusCode}`);
    const responseBody = await res.body.text();
    if (logProcess) console.log(`Response body: ${responseBody}`);
}

const wanted = 10_000;
let successes = 0;
Promise.allSettled(
    Array.from({length: wanted})
        .map(
            _ => processFile('../file.txt', 'http://localhost:8080/')
            .then(_ => successes++)
        )
)
    .then(() => {
        console.log(`S: ${successes} F:${wanted - successes}`);
    })
    // processFile('../file.txt', 'http://localhost:8080/')
    .then(() => {
        console.log('Process completed');
    })
    .catch(error => {
        console.error('Process failed with error:', error);
        process.exit(1);
    })

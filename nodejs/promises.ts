import {readFile} from 'node:fs/promises';
import {request} from 'undici';

async function processFile(filePath: string, requestUrl: string) {
    const data = await readFile(filePath);
    console.log(`Successfully read ${data.length} bytes from file`);

    console.log(`Sending HTTP request to: ${requestUrl}`);
    const res = await request(requestUrl, {
        method: 'POST',
        headers: {
            'Content-Type': 'text/plain',
            'Content-Length': data.length.toString(),
        },
        body: data,
    });
    console.log(`Received response with status code: ${res.statusCode}`);
    const responseBody = await res.body.text();
    console.log(`Response body: ${responseBody}`);
}

processFile('../file.txt', 'https://httpbin.org/anything')
    .then(() => {
        console.log('Process completed');
    })
    .catch(error => {
        console.error('Process failed with error:', error);
        process.exit(1);
    })
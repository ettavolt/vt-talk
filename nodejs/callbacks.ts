import * as fs from 'node:fs';
import * as https from 'node:https';

// Function to process a file and send HTTP request
function processFile(filePath: string, requestUrl: string, callback: (error?: Error) => void) {
    console.log(`Reading file: ${filePath}`);
    // Step 1: Read the file using callbacks
    fs.readFile(filePath, (err, data) => {
        if (err) {
            console.error(`Error opening file: ${err}`);
            return callback(err);
        }
        console.log(`Successfully read ${data.length} bytes from file`);

        console.log(`Sending HTTP request to: ${requestUrl}`);
        const req = https.request(requestUrl, {
            method: 'POST',
            headers: {
                'Content-Type': 'text/plain',
                'Content-Length': data.length
            }
        }, (res) => {

            console.log(`Received response with status code: ${res.statusCode}`);
            let responseBody = '';
            res.on('data', (chunk) => {
                responseBody += chunk;
            });
            res.on('end', () => {
                console.log(`Response body: ${responseBody}`);
                callback();
            });
        });
        req.on('error', (error) => {
            console.error(`Failed to send HTTP request: ${error}`);
            callback(error);
        });
        req.write(data);
        req.end();
    });
}

// Main execution
processFile('../file.txt', 'https://httpbin.org/anything', (error) => {
    if (error) {
        console.error('Process failed with error:', error);
        process.exit(1);
    } else {
        console.log('Process completed');
    }
})

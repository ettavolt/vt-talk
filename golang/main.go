package main

import (
	"bytes"
	"io"
	"log"
	"net/http"
	"os"
	"sync"
	"sync/atomic"
	"time"
)

func main() {
	//processOne()
	processMany()
	log.Println("Process completed")
}

func processOne() {
	// Create a channel to signal when the process is done
	done := make(chan bool)

	// Start process as a goroutine
	go func() {
		process("../file.txt", "http://localhost:8080/")
		// Signal that process is complete
		done <- true
	}()

	// Wait for the process to finish
	<-done
}

func processMany() {
	successes := atomic.Int32{}
	wg := sync.WaitGroup{}
	howMany := 1_000_000
	wg.Add(howMany)

	sem := make(chan struct{}, 100)

	for i := 0; i < howMany; i++ {
		// Acquire a permit: blocks if the buffer is used up
		sem <- struct{}{}

		go func() {
			defer func() {
				// Release the permit: frees a spot in the buffer
				<-sem
				wg.Done()
			}()

			if process("../file.txt", "http://localhost:8080/") {
				successes.Add(1)
			}
		}()
	}

	wg.Wait()
	log.Printf("S: %d F: %d\n", successes.Load(), int32(howMany)-successes.Load())
}

var logProcess = false
var client = &http.Client{Timeout: time.Second * 10}

func process(filePath string, url string) bool {
	// Step 1: Read the file
	if logProcess {
		log.Printf("Reading file: %s\n", filePath)
	}
	file, err := os.Open(filePath)
	if err != nil {
		if logProcess {
			log.Printf("Error opening file: %v\n", err)
		}
		return false
	}
	defer file.Close()
	data, err := io.ReadAll(file)
	if err != nil {
		if logProcess {
			log.Printf("Failed to read file: %v\n", err)
		}
		return false
	}
	if logProcess {
		log.Printf("Successfully read %d bytes from file\n", len(data))
	}

	// Step 2: Send HTTP request
	if logProcess {
		log.Printf("Sending HTTP request to: %s\n", url)
	}
	resp, err := client.Post(url, "text/plain", bytes.NewReader(data))
	if err != nil {
		if logProcess {
			log.Printf("Failed to send HTTP request: %v\n", err)
		}
		return false
	}
	defer resp.Body.Close()

	// Step 3: Process the response
	if logProcess {
		log.Printf("Received response with status code: %d\n", resp.StatusCode)
	}

	// Read and print response body
	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		if logProcess {
			log.Printf("Failed to read response body: %v\n", err)
		}
		return false
	}
	if logProcess {
		log.Printf("Response body: %s\n", respBody)
	}
	return true
}

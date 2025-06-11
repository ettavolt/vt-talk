package main

import (
	"bytes"
	"io"
	"log"
	"net/http"
	"os"
	"sync"
	"sync/atomic"
)

func main() {
	//processOne()
	processMany()
	log.Println("Process completed")
}

func processOne() {
	// Create a channel to signal when process is done
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
	failures := atomic.Int32{}
	wg := sync.WaitGroup{}
	howMany := 100_000
	wg.Add(howMany)

	for i := 0; i < howMany; i++ {
		go func() {
			if process("../file.txt", "http://localhost:8080/") {
				successes.Add(1)
			} else {
				failures.Add(1)
			}
			wg.Done()
		}()
	}

	wg.Wait()
	log.Printf("S: %d F: %d\n", successes.Load(), failures.Load())
}

func process(filePath string, url string) bool {
	// Step 1: Read the file
	log.Printf("Reading file: %s\n", filePath)
	file, err := os.Open(filePath)
	if err != nil {
		log.Printf("Error opening file: %v\n", err)
		return false
	}
	defer file.Close()
	data, err := io.ReadAll(file)
	if err != nil {
		log.Printf("Failed to read file: %v\n", err)
		return false
	}
	log.Printf("Successfully read %d bytes from file\n", len(data))

	// Step 2: Send HTTP request
	log.Printf("Sending HTTP request to: %s\n", url)
	resp, err := http.Post(url, "text/plain", bytes.NewReader(data))
	if err != nil {
		log.Printf("Failed to send HTTP request: %v\n", err)
		return false
	}
	defer resp.Body.Close()

	// Step 3: Process the response
	log.Printf("Received response with status code: %d\n", resp.StatusCode)

	// Read and print response body
	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		log.Printf("Failed to read response body: %v\n", err)
		return false
	}
	log.Printf("Response body: %s\n", respBody)
	return true
}

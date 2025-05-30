package main

import (
	"bytes"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
)

func main() {
	// Create a channel to signal when process is done
	done := make(chan bool)

	// Start process as a goroutine
	go func() {
		process("../file.txt", "https://httpbin.org/anything")
		// Signal that process is complete
		done <- true
	}()

	// Wait for the process to finish
	<-done
	log.Println("Process completed")
}

func process(filePath string, url string) {
	// Step 1: Read the file
	log.Printf("Reading file: %s\n", filePath)
	file, err := os.Open(filePath)
	if err != nil {
		log.Fatalf(fmt.Sprintf("Error opening file: %v", err), http.StatusInternalServerError)
		return
	}
	defer file.Close()
	data, err := io.ReadAll(file)
	if err != nil {
		log.Fatalf(fmt.Sprintf("Failed to read file: %v", err), http.StatusInternalServerError)
		return
	}
	log.Printf("Successfully read %d bytes from file\n", len(data))

	// Step 2: Send HTTP request
	log.Printf("Sending HTTP request to: %s\n", url)
	resp, err := http.Post(url, "text/plain", bytes.NewReader(data))
	if err != nil {
		log.Fatalf(fmt.Sprintf("Failed to send HTTP request: %v", err), http.StatusInternalServerError)
		return
	}
	defer resp.Body.Close()

	// Step 3: Process the response
	log.Printf("Received response with status code: %d\n", resp.StatusCode)

	// Read and print response body
	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		log.Fatalf(fmt.Sprintf("Failed to read response body: %v", err), http.StatusInternalServerError)
		return
	}
	log.Printf("Response body: %s\n", respBody)
}

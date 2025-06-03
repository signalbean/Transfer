document.addEventListener('DOMContentLoaded', () => {
    const dropZone = document.getElementById('drop-zone');
    const fileInput = document.getElementById('file-input');
    const filesTableBody = document.querySelector('#files-table tbody');
    const uploadProgressContainer = document.getElementById('upload-progress-container');
    const noFilesMessage = document.getElementById('no-files-message');

    // --- File Listing ---
    async function fetchFiles() {
        try {
            const response = await fetch('/api/files');
            if (!response.ok) {
                const errorText = await response.text();
                console.error('Error fetching files:', response.status, errorText);
                filesTableBody.innerHTML = `<tr><td colspan="5">Error loading files: ${errorText}</td></tr>`;
                noFilesMessage.style.display = 'none';
                return;
            }
            const data = await response.json();
            renderFiles(data.files);
        } catch (error) {
            console.error('Failed to fetch files:', error);
            filesTableBody.innerHTML = `<tr><td colspan="5">Could not connect to server or error fetching files.</td></tr>`;
            noFilesMessage.style.display = 'none';
        }
    }

    function renderFiles(files) {
        filesTableBody.innerHTML = ''; // Clear existing files
        if (!files || files.length === 0) {
            noFilesMessage.style.display = 'block';
            return;
        }
        noFilesMessage.style.display = 'none';

        files.forEach(file => {
            const row = filesTableBody.insertRow();
            row.insertCell().textContent = file.name;
            row.insertCell().textContent = file.formattedSize || file.size; // Use formattedSize if available
            row.insertCell().textContent = file.lastModified;
            row.insertCell().textContent = file.type;

            const actionsCell = row.insertCell();
            const downloadLink = document.createElement('a');
            downloadLink.href = file.downloadUrl;
            downloadLink.textContent = 'Download';
            downloadLink.className = 'action-button';
            downloadLink.setAttribute('download', file.name); // Suggest filename for download
            actionsCell.appendChild(downloadLink);

            const deleteButton = document.createElement('button');
            deleteButton.textContent = 'Delete';
            deleteButton.className = 'action-button delete-button';
            deleteButton.onclick = () => confirmDeleteFile(file.name);
            actionsCell.appendChild(deleteButton);
        });
    }

    async function confirmDeleteFile(fileName) {
        if (confirm(`Are you sure you want to delete "${fileName}"?`)) {
            await deleteFile(fileName);
        }
    }

    async function deleteFile(fileName) {
        try {
            const response = await fetch('/api/delete', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json', // Or 'application/x-www-form-urlencoded'
                },
                body: JSON.stringify({ filename: fileName }) // Send as JSON
                // Or for form data: new URLSearchParams({ 'filename': fileName })
            });
            const result = await response.json();
            if (response.ok) {
                fetchFiles(); // Refresh file list
            } else {
                alert(`Error deleting file: ${result.error || 'Unknown error'}`);
            }
        } catch (error) {
            console.error('Failed to delete file:', error);
            alert(`Failed to send delete request for "${fileName}".`);
        }
    }


    // --- Drag and Drop & File Upload ---
    dropZone.addEventListener('click', () => fileInput.click());

    dropZone.addEventListener('dragover', (event) => {
        event.preventDefault();
        dropZone.classList.add('dragover');
    });

    dropZone.addEventListener('dragleave', () => {
        dropZone.classList.remove('dragover');
    });

    dropZone.addEventListener('drop', (event) => {
        event.preventDefault();
        dropZone.classList.remove('dragover');
        const files = event.dataTransfer.files;
        if (files.length > 0) {
            handleFiles(files);
        }
    });

    fileInput.addEventListener('change', (event) => {
        const files = event.target.files;
        if (files.length > 0) {
            handleFiles(files);
        }
    });

    function handleFiles(files) {
        uploadProgressContainer.innerHTML = ''; // Clear previous progress
        Array.from(files).forEach(file => {
            uploadFile(file);
        });
    }

    function uploadFile(file) {
        const formData = new FormData();
        formData.append('file', file, file.name); // 'file' is the field name server expects, then the file object and its name

        const xhr = new XMLHttpRequest();

        // Create progress display for this file
        const progressItem = document.createElement('div');
        progressItem.className = 'progress-bar-item';
        const fileNameSpan = document.createElement('span');
        fileNameSpan.textContent = `${file.name} (${formatBytes(file.size)}): `;
        const progressBar = document.createElement('div');
        progressBar.className = 'progress-bar';
        const progressBarFill = document.createElement('div');
        progressBarFill.className = 'progress-bar-fill';
        const progressStatus = document.createElement('span');
        progressStatus.className = 'progress-bar-status';

        progressBar.appendChild(progressBarFill);
        progressItem.appendChild(fileNameSpan);
        progressItem.appendChild(progressBar);
        progressItem.appendChild(progressStatus);
        uploadProgressContainer.appendChild(progressItem);


        xhr.upload.addEventListener('progress', (event) => {
            if (event.lengthComputable) {
                const percentComplete = (event.loaded / event.total) * 100;
                progressBarFill.style.width = percentComplete.toFixed(2) + '%';
                progressStatus.textContent = `${percentComplete.toFixed(0)}%`;
            }
        });

        xhr.addEventListener('load', () => {
            if (xhr.status >= 200 && xhr.status < 300) {
                progressBarFill.style.backgroundColor = '#28a745'; // Green for success
                progressStatus.textContent = `Success: ${xhr.responseText}`;
                fetchFiles(); // Refresh file list after successful upload
            } else {
                progressBarFill.style.backgroundColor = '#dc3545'; // Red for error
                progressStatus.textContent = `Error: ${xhr.status} - ${xhr.responseText || 'Upload failed'}`;
                console.error('Upload failed:', xhr.status, xhr.responseText);
            }
        });

        xhr.addEventListener('error', () => {
            progressBarFill.style.backgroundColor = '#dc3545';
            progressStatus.textContent = 'Network Error';
            console.error('Upload error (network).');
        });

        xhr.open('POST', '/api/upload', true);
        // For some servers/NanoHTTPD setups, you might need to set the X-File-Name header for original filename
        // xhr.setRequestHeader('X-File-Name', file.name);
        xhr.send(formData);
    }

    function formatBytes(bytes, decimals = 2) {
        if (bytes === 0) return '0 Bytes';
        const k = 1024;
        const dm = decimals < 0 ? 0 : decimals;
        const sizes = ['Bytes', 'KB', 'MB', 'GB', 'TB', 'PB', 'EB', 'ZB', 'YB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(dm)) + ' ' + sizes[i];
    }

    // Initial load
    fetchFiles();
});
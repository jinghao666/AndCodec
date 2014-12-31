#include "andqueue.h"
#include <stddef.h>
#include "andsysutil.h"
#include "andlog.h"

static int and_queue_get_pos(filesize_t pos, int size);

int and_queue_init(SimpleQueue *p_queue, unsigned int data_len, unsigned int max_num)
{
	//and_log_writeline_simple(0, LOG_DEBUG, "queue init");

	if(!p_queue) {
		and_log_writeline_simple(0, LOG_ERROR, "queue not allocated");
		return -1;
	}

	p_queue->size		= max_num;
	p_queue->abs_r_pos	= 0;
	p_queue->abs_w_pos	= 0;
	p_queue->data_len	= data_len;

	p_queue->array = (void *)and_sysutil_malloc( data_len * max_num );
	if(!p_queue->array) {
		and_log_writeline_simple(0, LOG_DEBUG, "failed to alloc queue data"); 
		return -1;
	}
	and_sysutil_memclr(p_queue->array, data_len * max_num);

	and_log_writeline_easy(0, LOG_INFO, "queue inited, every %d byte for %d",
		data_len, max_num);
	return 0;
}
	
int and_queue_put(SimpleQueue *p_queue, void *p_data)
{
	if(!p_queue) {
		and_log_writeline_simple(0, LOG_ERROR, "queue not allocated");
		return -1;
	}

	if(!p_data) {
		and_log_writeline_simple(0, LOG_ERROR, "push null data");
		return -1;
	}

	const char *p_str = (char *)p_data;
	and_log_writeline_easy(0, LOG_DEBUG, "queue push 0x%02x 0x%02x", p_str[0], p_str[1]); 

	if(p_queue->abs_w_pos >= p_queue->abs_r_pos + p_queue->size) {
		and_log_writeline_easy(0, LOG_ERROR, "queue overflowed (r/w pos)%lld.%lld", 
			p_queue->abs_r_pos, p_queue->abs_w_pos);
		return -1;
	}
	
	int pos = and_queue_get_pos(p_queue->abs_w_pos, p_queue->size);
	//p_queue->array[pos] = data;
	int offset = p_queue->data_len * pos;
	and_sysutil_memcpy(p_queue->array + offset, p_data, p_queue->data_len);
	p_queue->abs_w_pos++;
	return 0;
}

int and_queue_get(SimpleQueue *p_queue, void *p_data)
{
	//and_log_writeline_simple(0, LOG_DEBUG, "queue pop");

	if(!p_queue) {
		and_log_writeline_simple(0, LOG_ERROR, "queue not allocated");
		return -1;
	}

	if(p_queue->abs_r_pos >= p_queue->abs_w_pos) {
		and_log_writeline_easy(0, LOG_ERROR, "queue underflowed (r/w pos)%lld.%lld", 
			p_queue->abs_r_pos, p_queue->abs_w_pos);
		return -1;
	}
	
	int pos = and_queue_get_pos(p_queue->abs_r_pos, p_queue->size);
	//*p_data = p_queue->array[pos];
	int offset = p_queue->data_len * pos;
	and_sysutil_memcpy(p_data, p_queue->array + offset, p_queue->data_len);
	p_queue->abs_r_pos++;

	const char *p_str = (char *)p_data;
	and_log_writeline_easy(0, LOG_DEBUG, "queue pop 0x%02x 0x%02x", p_str[0], p_str[1]); 
	return 0;
}

void and_queue_close(SimpleQueue *p_queue)
{
	//and_log_writeline_simple(0, LOG_DEBUG, "queue close");

	if(!p_queue) {
		and_log_writeline_simple(0, LOG_ERROR, "queue not allocated");
		return;
	}

	if(p_queue->array) {
		and_sysutil_free(p_queue->array);
		p_queue->array = NULL;
	}

	and_log_writeline_easy(0, LOG_INFO, "queue closed, (r/w pos)%lld.%lld left %lld", 
		p_queue->abs_r_pos, p_queue->abs_w_pos, p_queue->abs_w_pos - p_queue->abs_r_pos);
}

int and_queue_used(SimpleQueue *p_queue)
{
	if(!p_queue) {
		and_log_writeline_simple(0, LOG_ERROR, "queue not allocated");
		return -1;
	}

	return (int)(p_queue->abs_w_pos - p_queue->abs_r_pos);
}

static int and_queue_get_pos(filesize_t pos, int size)
{
	return pos % size;
}

